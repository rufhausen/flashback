package dev.gtaylor.flashback.radar

import dev.gtaylor.flashback.karoo.AntLightMode

enum class StatusLevel { IDLE, ARMED, FLASHING, PROBLEM }

data class FlashbackStatus(
    val level: StatusLevel,
    val headline: String,
    val detail: String,
)

/**
 * What the ride-screen field shows. The detail line is built from the mode the *light reported*,
 * not the mode we sent, so a ✓ means the light really is doing it.
 */
object FlashbackStatusFormatter {
    fun format(
        riding: Boolean,
        enabled: Boolean,
        healthProblem: String?,
        alertMode: String?,
        reportedModes: List<String?>,
    ): FlashbackStatus {
        if (!enabled) return FlashbackStatus(StatusLevel.IDLE, "OFF", "Radar flash disabled")
        if (!riding) return FlashbackStatus(StatusLevel.IDLE, "STANDBY", "Starts with the ride")
        if (healthProblem != null) return FlashbackStatus(StatusLevel.PROBLEM, "NOT ACTIVE", healthProblem)

        // Several selected lights: show the worst one, i.e. the first that doesn't match.
        val reported = reportedModes.firstOrNull { it != (alertMode ?: it) } ?: reportedModes.firstOrNull()
        return if (alertMode != null) {
            FlashbackStatus(StatusLevel.FLASHING, "FLASHING", lightLine(reported, expected = alertMode))
        } else {
            FlashbackStatus(StatusLevel.ARMED, "ARMED", lightLine(reported, expected = null))
        }
    }

    private fun lightLine(
        reported: String?,
        expected: String?,
    ): String {
        if (reported == null) return "Light: waiting for report"
        val name = displayName(reported)
        return when {
            expected == null -> "Light: $name"
            reported == expected -> "Light: $name ✓"
            else -> "Light: $name ✗ (sent ${displayName(expected)})"
        }
    }

    fun displayName(mode: String): String = AntLightMode.fromKarooName(mode)?.displayName ?: mode
}
