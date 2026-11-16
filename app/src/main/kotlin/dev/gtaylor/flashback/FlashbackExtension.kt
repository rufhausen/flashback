package dev.gtaylor.flashback

import dev.gtaylor.flashback.data.FlashbackSettings
import dev.gtaylor.flashback.data.SettingsRepository
import dev.gtaylor.flashback.karoo.KarooLightControl
import dev.gtaylor.flashback.radar.FlashbackStatus
import dev.gtaylor.flashback.radar.FlashbackStatusFormatter
import dev.gtaylor.flashback.radar.HealthEvent
import dev.gtaylor.flashback.radar.LightHealthInput
import dev.gtaylor.flashback.radar.LightHealthMonitor
import dev.gtaylor.flashback.radar.RadarFlashController
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors

data class KarooLight(
    val id: String,
    val name: String,
    val manufacturer: String?,
)

class FlashbackExtension : KarooExtension("flashback", BuildConfig.VERSION_NAME) {
    companion object {
        private const val TAG = "Flashback"
        private const val BIKE_LIGHT_DATA_TYPE = "TYPE_BIKE_LIGHT_ID"
        private const val ANT_DEVICE_TYPE_BIKE_LIGHT = 35
        private const val TEST_FLASH_MS = 3_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L

        @Volatile
        var instance: FlashbackExtension? = null
            private set
    }

    lateinit var lightControl: KarooLightControl
        private set
    private lateinit var karooSystem: KarooSystemService
    private lateinit var repository: SettingsRepository
    private lateinit var flash: RadarFlashController

    // Everything stateful runs on the main thread; binder calls go to a single worker so they stay ordered.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lightWorker = Executors.newSingleThreadExecutor()

    private val _lights = MutableStateFlow<List<KarooLight>>(emptyList())
    val lights: StateFlow<List<KarooLight>> = _lights

    private val _radarStatus = MutableStateFlow("Not riding")
    val radarStatus: StateFlow<String> = _radarStatus

    /** Current light-control problem, or null when all good / not riding. Shown in settings. */
    private val _healthProblem = MutableStateFlow<String?>(null)
    val healthProblem: StateFlow<String?> = _healthProblem

    private val health = LightHealthMonitor()
    private var healthJob: Job? = null

    @Volatile private var lastCommandFailed = false

    private var radarConsumerId: String? = null
    private var devicesConsumerId: String? = null
    private val riding = MutableStateFlow(false)
    private var rideActive: Boolean
        get() = riding.value
        set(value) {
            riding.value = value
        }
    private val settingsState = MutableStateFlow(FlashbackSettings())

    /** Everything the ride-screen field and settings screen show about whether this is working. */
    lateinit var status: StateFlow<FlashbackStatus>
        private set

    override val types by lazy { listOf(StatusDataType(status)) }
    private var settingsUiActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        karooSystem = KarooSystemService(applicationContext)
        repository = SettingsRepository(applicationContext)
        lightControl = KarooLightControl(applicationContext)
        flash =
            RadarFlashController(
                scope = scope,
                setMode = { id, mode -> sendMode(id, mode) },
                reportedModeOf = { id -> lightControl.currentModes.value[id] },
            )

        status =
            combine(riding, settingsState, _healthProblem, flash.alertMode, lightControl.currentModes) {
                    isRiding, settings, problem, alertMode, modes ->
                FlashbackStatusFormatter.format(
                    riding = isRiding,
                    enabled = settings.enabled,
                    healthProblem = problem,
                    alertMode = alertMode,
                    reportedModes = settings.lightIds.map { modes[it] },
                )
            }.stateIn(scope, SharingStarted.Eagerly, FlashbackStatusFormatter.format(false, true, null, null, emptyList()))

        scope.launch { repository.settings.collect { applySettings(it) } }
        scope.launch {
            lightControl.currentModes.collect { modes ->
                modes.forEach { (id, mode) -> flash.onLightModeReported(id, mode) }
            }
        }

        karooSystem.connect { connected ->
            Timber.d("$TAG: Karoo system connected=$connected")
            if (connected) karooSystem.addConsumer<RideState> { onRideState(it) }
        }
    }

    private fun applySettings(settings: FlashbackSettings) {
        settingsState.value = settings
        flash.updateSettings(settings)
        if (rideActive) syncRadarStream()
    }

    private fun onRideState(state: RideState) {
        when (state) {
            // Keep watching while paused — that's exactly when you're stopped at the side of the road.
            is RideState.Recording, is RideState.Paused -> {
                if (rideActive) return
                rideActive = true
                Timber.i("$TAG: ride started")
                lightControl.bind()
                watchSavedDevices()
                syncRadarStream()
                startHealthChecks()
            }

            is RideState.Idle -> {
                if (!rideActive) return
                rideActive = false
                Timber.i("$TAG: ride ended")
                stopRadarStream()
                stopHealthChecks()
                flash.reset()
                _radarStatus.value = "Not riding"
                // Let the restore command go out before dropping the session.
                lightWorker.execute { scope.launch { releaseLightsIfIdle() } }
            }
        }
    }

    private fun sendMode(
        deviceId: String,
        mode: String,
    ) {
        lightWorker.execute { lastCommandFailed = !lightControl.setLightMode(deviceId, mode) }
    }

    private fun startHealthChecks() {
        if (healthJob != null) return
        health.reset()
        healthJob =
            scope.launch {
                while (true) {
                    delay(HEALTH_CHECK_INTERVAL_MS)
                    checkHealth()
                }
            }
    }

    private fun stopHealthChecks() {
        healthJob?.cancel()
        healthJob = null
        _healthProblem.value = null
    }

    private fun checkHealth() {
        val settings = flash.settings
        if (!settings.enabled) {
            health.reset()
            _healthProblem.value = null
            return
        }
        val input =
            LightHealthInput(
                serviceReady = lightControl.isConnected(),
                pairedSelected = _lights.value.filter { it.id in settings.lightIds }.associate { it.id to it.name },
                selectedIds = settings.lightIds,
                connectionStates = lightControl.connectionStates.value,
                lastCommandFailed = lastCommandFailed,
            )
        when (val event = health.check(input, System.currentTimeMillis())) {
            is HealthEvent.Problem -> {
                _healthProblem.value = event.message
                if (settings.healthAlerts) showAlert("Flashback NOT active", event.message, warn = true)
            }

            HealthEvent.Recovered -> {
                _healthProblem.value = null
                if (settings.healthAlerts) showAlert("Flashback active", "Light control restored", warn = false)
            }

            null -> {}
        }
    }

    private fun showAlert(
        title: String,
        detail: String?,
        warn: Boolean,
    ) {
        if (warn) {
            karooSystem.dispatch(
                PlayBeepPattern(
                    listOf(
                        PlayBeepPattern.Tone(frequency = 1_000, durationMs = 150),
                        PlayBeepPattern.Tone(frequency = null, durationMs = 100),
                        PlayBeepPattern.Tone(frequency = 600, durationMs = 300),
                    ),
                ),
            )
        }
        karooSystem.dispatch(
            InRideAlert(
                id = "flashback-health",
                icon = R.drawable.ic_flashback,
                title = title,
                detail = detail,
                autoDismissMs = if (warn) 15_000 else 4_000,
                backgroundColor = if (warn) android.R.color.holo_red_dark else android.R.color.black,
                textColor = android.R.color.white,
            ),
        )
    }

    private fun syncRadarStream() {
        val wanted = flash.settings.enabled && flash.settings.lightIds.isNotEmpty()
        if (wanted && radarConsumerId == null) {
            Timber.i("$TAG: starting radar stream")
            radarConsumerId =
                karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.RADAR)) { event: OnStreamState ->
                    onRadarState(event.state)
                }
        } else if (!wanted) {
            stopRadarStream()
            flash.reset()
            _radarStatus.value = if (flash.settings.enabled) "No light selected" else "Disabled"
        }
    }

    private fun stopRadarStream() {
        radarConsumerId?.let { karooSystem.removeConsumer(it) }
        radarConsumerId = null
    }

    private fun onRadarState(state: StreamState) {
        when (state) {
            is StreamState.Streaming -> {
                val level = state.dataPoint.values[DataType.Field.RADAR_THREAT_LEVEL]?.toInt() ?: 0
                _radarStatus.value = if (level > 0) "Vehicle detected" else "Clear"
                flash.onThreatLevel(level)
            }

            else -> {
                _radarStatus.value = "Radar ${state::class.simpleName?.lowercase()}"
                flash.onRadarUnavailable()
            }
        }
    }

    /** Tracks the ANT+ lights paired in Karoo's sensor settings, and hooks each one up for mode reporting. */
    private fun watchSavedDevices() {
        if (devicesConsumerId != null) return
        devicesConsumerId =
            karooSystem.addConsumer<SavedDevices> { saved ->
                val lights =
                    saved.devices
                        .filter { it.enabled && BIKE_LIGHT_DATA_TYPE in it.supportedDataTypes }
                        .filter { it.id.split("-").getOrNull(1)?.toIntOrNull() == ANT_DEVICE_TYPE_BIKE_LIGHT }
                        .map { KarooLight(it.id, it.name, it.details.manufacturer) }
                _lights.value = lights
                lights.forEach {
                    lightControl.registerConnectionState(it.id)
                    lightControl.registerForLightParameters(it.id)
                }
            }
    }

    private fun stopWatchingSavedDevices() {
        devicesConsumerId?.let { karooSystem.removeConsumer(it) }
        devicesConsumerId = null
    }

    private fun releaseLightsIfIdle() {
        if (rideActive || settingsUiActive) return
        stopWatchingSavedDevices()
        lightControl.unbind()
    }

    fun setSettingsUiActive(active: Boolean) {
        scope.launch {
            settingsUiActive = active
            if (active) {
                lightControl.bind()
                watchSavedDevices()
            } else {
                releaseLightsIfIdle()
            }
        }
    }

    /** Flash a light briefly from the settings screen, then put it back. */
    fun testFlash(
        deviceId: String,
        mode: String,
    ) {
        scope.launch {
            val previous = lightControl.currentModes.value[deviceId]?.takeIf { it != mode } ?: "OFF"
            sendMode(deviceId, mode)
            delay(TEST_FLASH_MS)
            sendMode(deviceId, previous)
        }
    }

    override fun onBonusAction(actionId: String) {
        if (actionId != "toggle-flashback") return
        scope.launch {
            val enabled = repository.update { it.copy(enabled = !it.enabled) }.enabled
            karooSystem.dispatch(
                InRideAlert(
                    id = "flashback-toggle",
                    icon = R.drawable.ic_flashback,
                    title = if (enabled) "Radar flash ON" else "Radar flash OFF",
                    detail = null,
                    autoDismissMs = 2_500,
                    backgroundColor = android.R.color.black,
                    textColor = android.R.color.white,
                ),
            )
            health.reset() // re-check from scratch after a manual toggle
        }
    }

    override fun onDestroy() {
        Timber.d("$TAG: onDestroy")
        stopRadarStream()
        stopHealthChecks()
        stopWatchingSavedDevices()
        flash.reset()
        lightWorker.execute { lightControl.unbind() }
        lightWorker.shutdown()
        karooSystem.disconnect()
        scope.cancel()
        instance = null
        super.onDestroy()
    }
}
