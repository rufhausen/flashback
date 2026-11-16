package dev.gtaylor.flashback

import dev.gtaylor.flashback.data.FlashbackSettings
import dev.gtaylor.flashback.data.SettingsRepository
import dev.gtaylor.flashback.karoo.KarooLightControl
import dev.gtaylor.flashback.radar.RadarFlashController
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private var radarConsumerId: String? = null
    private var devicesConsumerId: String? = null
    private var rideActive = false
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
                setMode = { id, mode -> lightWorker.execute { lightControl.setMode(id, mode) } },
                reportedModeOf = { id -> lightControl.currentModes.value[id] },
            )

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
            }

            is RideState.Idle -> {
                if (!rideActive) return
                rideActive = false
                Timber.i("$TAG: ride ended")
                stopRadarStream()
                flash.reset()
                _radarStatus.value = "Not riding"
                // Let the restore command go out before dropping the session.
                lightWorker.execute { scope.launch { releaseLightsIfIdle() } }
            }
        }
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
            lightWorker.execute { lightControl.setMode(deviceId, mode) }
            delay(TEST_FLASH_MS)
            lightWorker.execute { lightControl.setMode(deviceId, previous) }
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
        }
    }

    override fun onDestroy() {
        Timber.d("$TAG: onDestroy")
        stopRadarStream()
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
