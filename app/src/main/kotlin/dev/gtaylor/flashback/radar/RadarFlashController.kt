package dev.gtaylor.flashback.radar

import dev.gtaylor.flashback.data.AfterAlert
import dev.gtaylor.flashback.data.FlashbackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Flashes the configured lights while the radar reports a vehicle and puts them back afterwards.
 *
 * The light is left alone at all other times, so Karoo's own light control (or whatever mode you
 * set manually) stays in charge. All entry points must be called from a single thread/dispatcher.
 */
class RadarFlashController(
    private val scope: CoroutineScope,
    private val setMode: (deviceId: String, mode: String) -> Unit,
    private val reportedModeOf: (deviceId: String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        const val TAG = "RadarFlash"
        const val REASSERT_MIN_INTERVAL_MS = 2_000L
        const val FAST_APPROACH = 2
    }

    var settings: FlashbackSettings = FlashbackSettings()
        private set

    /** Mode each light was in when the current alert started; non-empty only while alerting. */
    private val restoreModes = mutableMapOf<String, String>()
    private var clearJob: Job? = null
    private var lastReassertAt = 0L

    /** A fast-approaching car was seen during this alert. Sticks until clear so the mode doesn't bounce. */
    private var escalated = false

    val isAlerting: Boolean get() = restoreModes.isNotEmpty()

    /** Mode currently being commanded because of a vehicle, or null when not alerting. */
    private val _alertMode = MutableStateFlow<String?>(null)
    val alertMode: StateFlow<String?> = _alertMode

    private fun publish() {
        _alertMode.value = if (isAlerting) activeMode else null
    }

    private val activeMode: String
        get() = if (escalated) settings.fastAlertMode ?: settings.alertMode else settings.alertMode

    fun updateSettings(new: FlashbackSettings) {
        val old = settings
        settings = new
        if (!isAlerting) return
        if (!new.enabled) {
            endAlert()
            return
        }
        // Lights removed from the set get restored; the rest get the (possibly new) alert mode.
        (old.lightIds - new.lightIds).forEach { id -> restoreModes.remove(id)?.let { setMode(id, it) } }
        if (new.lightIds != old.lightIds || new.alertMode != old.alertMode || new.fastAlertMode != old.fastAlertMode) {
            new.lightIds.forEach { id ->
                restoreModes.getOrPut(id) { snapshot(id) }
                setMode(id, activeMode)
            }
        }
        if (restoreModes.isEmpty()) escalated = false
        publish()
    }

    fun onThreatLevel(level: Int) {
        if (!settings.enabled || settings.lightIds.isEmpty()) return
        if (level >= settings.minThreatLevel) {
            clearJob?.cancel()
            clearJob = null
            if (!isAlerting) {
                startAlert(level)
            } else if (level >= FAST_APPROACH && !escalated) {
                escalate()
            }
        } else if (isAlerting && clearJob == null) {
            val holdMs = settings.clearHoldSeconds.coerceAtLeast(0) * 1_000L
            clearJob =
                scope.launch {
                    if (holdMs > 0) delay(holdMs)
                    clearJob = null
                    endAlert()
                }
        }
    }

    /** Radar dropped out — don't leave a light strobing forever. */
    fun onRadarUnavailable() {
        if (isAlerting) Timber.i("$TAG: radar unavailable mid-alert, restoring lights")
        clearJob?.cancel()
        clearJob = null
        endAlert()
    }

    /**
     * Called whenever a light reports its mode. If something else (Karoo's auto control, a button
     * press on the light) knocks it out of the alert mode mid-alert, put it back — rate limited so
     * we don't get into a shoving match.
     */
    fun onLightModeReported(
        deviceId: String,
        mode: String,
    ) {
        if (deviceId !in restoreModes || mode == activeMode) return
        val t = now()
        if (t - lastReassertAt < REASSERT_MIN_INTERVAL_MS) return
        lastReassertAt = t
        Timber.d("$TAG: $deviceId drifted to $mode during alert, re-asserting $activeMode")
        setMode(deviceId, activeMode)
    }

    /** Ride over / extension shutting down: restore and forget everything. */
    fun reset() {
        clearJob?.cancel()
        clearJob = null
        endAlert()
    }

    private fun startAlert(level: Int) {
        escalated = level >= FAST_APPROACH
        Timber.i("$TAG: vehicle detected (threat=$level), $activeMode on ${settings.lightIds}")
        for (id in settings.lightIds) {
            restoreModes[id] = snapshot(id)
            setMode(id, activeMode)
        }
        publish()
    }

    private fun escalate() {
        val before = activeMode
        escalated = true
        if (activeMode == before) return
        Timber.i("$TAG: fast approach, escalating to $activeMode")
        restoreModes.keys.forEach { setMode(it, activeMode) }
        publish()
    }

    private fun endAlert() {
        escalated = false
        if (!isAlerting) return
        Timber.i("$TAG: road clear, restoring lights")
        for ((id, previous) in restoreModes) {
            val target = if (settings.afterAlert == AfterAlert.RESTORE_PREVIOUS) previous else "OFF"
            setMode(id, target)
        }
        restoreModes.clear()
        publish()
    }

    private fun snapshot(deviceId: String): String =
        reportedModeOf(deviceId)
            ?.takeIf { it != settings.alertMode && it != settings.fastAlertMode && it != "UNKNOWN" }
            ?: "OFF"
}
