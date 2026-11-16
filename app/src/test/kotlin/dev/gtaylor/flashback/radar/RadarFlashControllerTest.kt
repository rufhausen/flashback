package dev.gtaylor.flashback.radar

import dev.gtaylor.flashback.data.AfterAlert
import dev.gtaylor.flashback.data.FlashbackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadarFlashControllerTest {
    private val varia = "varia-35-1234"
    private val commands = mutableListOf<Pair<String, String>>()
    private val reported = mutableMapOf<String, String>()
    private var clock = 0L

    private fun TestScope.controller(settings: FlashbackSettings = FlashbackSettings(lightIds = setOf(varia))) =
        RadarFlashController(
            scope = this,
            setMode = { id, mode ->
                commands += id to mode
                reported[id] = mode
            },
            reportedModeOf = { reported[it] },
            now = { clock },
        ).also { it.updateSettings(settings) }

    @Test
    fun `flashes on vehicle and restores previous mode after hold`() =
        runTest {
            reported[varia] = "OFF"
            val c = controller()

            c.onThreatLevel(1)
            assertEquals(listOf(varia to "FAST_FLASH"), commands)

            c.onThreatLevel(0)
            advanceTimeBy(2_999)
            runCurrent()
            assertEquals(1, commands.size)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(varia to "OFF", commands.last())
            assertFalse(c.isAlerting)
        }

    @Test
    fun `restores a steady mode Karoo had set`() =
        runTest {
            reported[varia] = "STEADY4"
            val c = controller(FlashbackSettings(lightIds = setOf(varia), clearHoldSeconds = 0))

            c.onThreatLevel(2)
            c.onThreatLevel(0)
            runCurrent()

            assertEquals(listOf(varia to "FAST_FLASH", varia to "STEADY4"), commands)
        }

    @Test
    fun `turn-off option ignores previous mode`() =
        runTest {
            reported[varia] = "STEADY4"
            val c = controller(FlashbackSettings(lightIds = setOf(varia), clearHoldSeconds = 0, afterAlert = AfterAlert.OFF))

            c.onThreatLevel(1)
            c.onThreatLevel(0)
            runCurrent()

            assertEquals(varia to "OFF", commands.last())
        }

    @Test
    fun `second car during hold keeps flashing without extra commands`() =
        runTest {
            val c = controller()

            c.onThreatLevel(1)
            c.onThreatLevel(0)
            advanceTimeBy(1_500)
            c.onThreatLevel(1)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(listOf(varia to "FAST_FLASH"), commands)
            assertTrue(c.isAlerting)
        }

    @Test
    fun `fast-approach-only ignores level 1`() =
        runTest {
            val c = controller(FlashbackSettings(lightIds = setOf(varia), minThreatLevel = 2))

            c.onThreatLevel(1)
            assertTrue(commands.isEmpty())

            c.onThreatLevel(2)
            assertEquals(listOf(varia to "FAST_FLASH"), commands)
        }

    @Test
    fun `disabled does nothing`() =
        runTest {
            val c = controller(FlashbackSettings(enabled = false, lightIds = setOf(varia)))
            c.onThreatLevel(2)
            assertTrue(commands.isEmpty())
        }

    @Test
    fun `radar dropout restores immediately`() =
        runTest {
            reported[varia] = "OFF"
            val c = controller()

            c.onThreatLevel(1)
            c.onRadarUnavailable()

            assertEquals(varia to "OFF", commands.last())
            assertFalse(c.isAlerting)
        }

    @Test
    fun `re-asserts flash if something knocks the light out of it, rate limited`() =
        runTest {
            val c = controller()
            c.onThreatLevel(1)
            commands.clear()

            clock = 5_000
            c.onLightModeReported(varia, "STEADY4")
            c.onLightModeReported(varia, "STEADY4")
            assertEquals(listOf(varia to "FAST_FLASH"), commands)

            clock = 7_500
            c.onLightModeReported(varia, "STEADY4")
            assertEquals(2, commands.size)
        }

    @Test
    fun `mode reports outside an alert are ignored`() =
        runTest {
            val c = controller()
            clock = 10_000
            c.onLightModeReported(varia, "STEADY4")
            assertTrue(commands.isEmpty())
        }

    @Test
    fun `disabling mid-alert restores`() =
        runTest {
            reported[varia] = "SLOW_FLASH"
            val c = controller()

            c.onThreatLevel(1)
            c.updateSettings(c.settings.copy(enabled = false))

            assertEquals(varia to "SLOW_FLASH", commands.last())
            assertFalse(c.isAlerting)
        }

    @Test
    fun `unknown previous mode falls back to off`() =
        runTest {
            val c = controller(FlashbackSettings(lightIds = setOf(varia), clearHoldSeconds = 0))
            c.onThreatLevel(1)
            c.onThreatLevel(0)
            runCurrent()
            assertEquals(varia to "OFF", commands.last())
        }
}
