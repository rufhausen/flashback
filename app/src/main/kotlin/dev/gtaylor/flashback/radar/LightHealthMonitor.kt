package dev.gtaylor.flashback.radar

/** What the monitor wants the rider told. */
sealed interface HealthEvent {
    data class Problem(
        val message: String,
    ) : HealthEvent

    data object Recovered : HealthEvent
}

data class LightHealthInput(
    /** Karoo's SensorService light session is up (we got the LightCommand binder). */
    val serviceReady: Boolean,
    /** Selected light id → display name, for lights currently paired and enabled in Karoo. */
    val pairedSelected: Map<String, String>,
    val selectedIds: Set<String>,
    /** Last reported ANT+ connection state per light; missing = no report yet. */
    val connectionStates: Map<String, String>,
    /** The most recent light command we sent failed. */
    val lastCommandFailed: Boolean,
)

/**
 * Decides when to warn that Flashback can't actually control the light. A problem has to persist
 * for [persistMs] before it's reported (lights take a while to connect after ride start), each
 * distinct problem is reported once, and the rider is told when it clears.
 */
class LightHealthMonitor(
    private val persistMs: Long = 20_000,
) {
    private var pending: String? = null
    private var pendingSince = 0L
    private var reported: String? = null

    fun reset() {
        pending = null
        reported = null
    }

    fun check(
        input: LightHealthInput,
        now: Long,
    ): HealthEvent? {
        val problem = diagnose(input)
        if (problem == null) {
            pending = null
            return if (reported != null) {
                reported = null
                HealthEvent.Recovered
            } else {
                null
            }
        }
        if (problem != pending) {
            pending = problem
            pendingSince = now
        }
        if (now - pendingSince < persistMs || problem == reported) return null
        reported = problem
        return HealthEvent.Problem(problem)
    }

    private fun diagnose(input: LightHealthInput): String? {
        if (input.selectedIds.isEmpty()) return "No light selected"
        if (!input.serviceReady) return "Can't reach Karoo light control (firmware change?)"
        val missing = input.selectedIds - input.pairedSelected.keys
        if (missing.isNotEmpty()) return "Selected light isn't paired or is disabled in Karoo sensors"
        // Only trust explicit states — no report yet is not evidence of a problem.
        val down =
            input.pairedSelected.filterKeys { id ->
                input.connectionStates[id].let { it != null && it != "CONNECTED" }
            }
        if (down.isNotEmpty()) return "${down.values.joinToString()} not connected"
        if (input.lastCommandFailed) return "Light command failed"
        return null
    }
}
