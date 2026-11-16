package dev.gtaylor.flashback.radar

import org.junit.Assert.assertEquals
import org.junit.Test

class FlashbackStatusFormatterTest {
    private fun fmt(
        alertMode: String? = null,
        reported: List<String?> = listOf("OFF"),
        problem: String? = null,
        riding: Boolean = true,
        enabled: Boolean = true,
    ) = FlashbackStatusFormatter.format(riding, enabled, problem, alertMode, reported)

    @Test
    fun `armed shows the light's reported mode`() {
        assertEquals(FlashbackStatus(StatusLevel.ARMED, "ARMED", "Light: Off"), fmt())
    }

    @Test
    fun `flashing with light confirmation`() {
        assertEquals(
            FlashbackStatus(StatusLevel.FLASHING, "FLASHING", "Light: Fast Flash ✓"),
            fmt(alertMode = "FAST_FLASH", reported = listOf("FAST_FLASH")),
        )
    }

    @Test
    fun `flashing but light did not follow`() {
        assertEquals(
            "Light: Off ✗ (sent Fast Flash)",
            fmt(alertMode = "FAST_FLASH", reported = listOf("OFF")).detail,
        )
    }

    @Test
    fun `worst light wins when several are selected`() {
        assertEquals(
            "Light: Off ✗ (sent Fast Flash)",
            fmt(alertMode = "FAST_FLASH", reported = listOf("FAST_FLASH", "OFF")).detail,
        )
    }

    @Test
    fun `problem beats everything except disabled`() {
        assertEquals(StatusLevel.PROBLEM, fmt(alertMode = "FAST_FLASH", problem = "Light command failed").level)
        assertEquals("OFF", fmt(problem = "x", enabled = false).headline)
    }

    @Test
    fun `standby before the ride and no report yet`() {
        assertEquals("STANDBY", fmt(riding = false).headline)
        assertEquals("Light: waiting for report", fmt(reported = listOf(null)).detail)
    }
}
