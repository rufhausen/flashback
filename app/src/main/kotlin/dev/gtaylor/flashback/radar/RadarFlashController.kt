package dev.gtaylor.flashback.radar

import dev.gtaylor.flashback.data.AfterAlert
import dev.gtaylor.flashback.data.FlashbackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    }

    var settings: FlashbackSettings = FlashbackSettings()
        private set

    /** Mode each light was in when the current alert started; non-empty only while alerting. */
    private val restoreModes = mutableMapOf<String, String>()
    private var clearJob: Job? = null
    private var lastReassertAt = 0L

    val isAlerting: Boolean get() = restoreModes.isNotEmpty()

    fun updateSettings(new: FlashbackSettings) {
        val old = settings
        settings = new
        if (!isAlerting) return
        when {
            !new.enabled -> endAlert()
            new.lightIds != old.lightIds || new.alertMode != old.alertMode -> {
                // Lights removed from the set get restored; new or changed ones get the new alert mode.
                (old.lightIds - new.lightIds).forEach { id -> restoreModes.remove(id)?.let { setMode(id, it) } }
                new.lightIds.forEach { id ->
                    restoreModes.getOrPut(id) { snapshot(id) }
                    setMode(id, new.alertMode)
                }
            }
        }
    }

    fun onThreatLevel(level: Int) {
        if (!settings.enabled || settings.lightIds.isEmpty()) return
        if (level >= settings.minThreatLevel) {
            clearJob?.cancel()
            clearJob = null
            if (!isAlerting) startAlert(level)
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
        if (deviceId !in restoreModes || mode == settings.alertMode) return
        val t = now()
        if (t - lastReassertAt < REASSERT_MIN_INTERVAL_MS) return
        lastReassertAt = t
        Timber.d("$TAG: $deviceId drifted to $mode during alert, re-asserting ${settings.alertMode}")
        setMode(deviceId, settings.alertMode)
    }

    /** Ride over / extension shutting down: restore and forget everything. */
    fun reset() {
        clearJob?.cancel()
        clearJob = null
        endAlert()
    }

    private fun startAlert(level: Int) {
        Timber.i("$TAG: vehicle detected (threat=$level), flashing ${settings.lightIds}")
        for (id in settings.lightIds) {
            restoreModes[id] = snapshot(id)
            setMode(id, settings.alertMode)
        }
    }

    private fun endAlert() {
        if (!isAlerting) return
        Timber.i("$TAG: road clear, restoring lights")
        for ((id, previous) in restoreModes) {
            val target = if (settings.afterAlert == AfterAlert.RESTORE_PREVIOUS) previous else "OFF"
            setMode(id, target)
        }
        restoreModes.clear()
    }

    private fun snapshot(deviceId: String): String =
        reportedModeOf(deviceId)
            ?.takeIf { it != settings.alertMode && it != "UNKNOWN" }
            ?: "OFF"
}
