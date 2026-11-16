package dev.gtaylor.flashback.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightHealthMonitorTest {
    private val varia = "varia-35-1"
    private val ok =
        LightHealthInput(
            serviceReady = true,
            pairedSelected = mapOf(varia to "Varia RTL515"),
            selectedIds = setOf(varia),
            connectionStates = mapOf(varia to "CONNECTED"),
            lastCommandFailed = false,
        )

    @Test
    fun `healthy stays quiet`() {
        val m = LightHealthMonitor(persistMs = 20_000)
        assertNull(m.check(ok, 0))
        assertNull(m.check(ok, 60_000))
    }

    @Test
    fun `problem must persist before alerting, then alerts once`() {
        val m = LightHealthMonitor(persistMs = 20_000)
        val broken = ok.copy(serviceReady = false)
        assertNull(m.check(broken, 0))
        assertNull(m.check(broken, 19_999))
        assertEquals(HealthEvent.Problem("Can't reach Karoo light control (firmware change?)"), m.check(broken, 20_000))
        assertNull(m.check(broken, 40_000))
    }

    @Test
    fun `transient blip during connect is not reported`() {
        val m = LightHealthMonitor(persistMs = 20_000)
        assertNull(m.check(ok.copy(connectionStates = mapOf(varia to "SEARCHING")), 0))
        assertNull(m.check(ok, 10_000))
        assertNull(m.check(ok, 40_000))
    }

    @Test
    fun `recovery is announced after a reported problem`() {
        val m = LightHealthMonitor(persistMs = 0)
        m.check(ok.copy(connectionStates = mapOf(varia to "DISCONNECTED")), 0)
        assertEquals(HealthEvent.Recovered, m.check(ok, 5_000))
        assertNull(m.check(ok, 10_000))
    }

    @Test
    fun `no state report yet is not treated as disconnected`() {
        val m = LightHealthMonitor(persistMs = 0)
        assertNull(m.check(ok.copy(connectionStates = emptyMap()), 0))
    }

    @Test
    fun `diagnoses each failure`() {
        fun problem(input: LightHealthInput) = (LightHealthMonitor(persistMs = 0).check(input, 0) as HealthEvent.Problem).message
        assertEquals("No light selected", problem(ok.copy(selectedIds = emptySet())))
        assertEquals("Selected light isn't paired or is disabled in Karoo sensors", problem(ok.copy(pairedSelected = emptyMap())))
        assertEquals("Varia RTL515 not connected", problem(ok.copy(connectionStates = mapOf(varia to "DISCONNECTED"))))
        assertEquals("Light command failed", problem(ok.copy(lastCommandFailed = true)))
    }
}
