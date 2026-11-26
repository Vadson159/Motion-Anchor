// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRatesTest {
    /** Draws in one second of vsync frames at [refreshRate], with a little timestamp jitter. */
    private fun drawsPerSecond(chosen: Int, refreshRate: Int): Int {
        val pacer = FramePacer().apply { setRate(chosen) }
        val period = 1_000_000_000.0 / refreshRate
        var draws = 0
        for (frame in 0 until refreshRate * 3) {
            val jitter = if (frame % 2 == 0) 300_000L else -300_000L
            val drew = pacer.shouldDraw((frame * period).toLong() + jitter)
            if (frame >= refreshRate && drew) draws++
        }
        return draws / 2
    }

    @Test
    fun divisorsOfTheRefreshRateAreExact() {
        assertEquals(60, drawsPerSecond(60, 120))
        assertEquals(30, drawsPerSecond(30, 60))
        assertEquals(60, drawsPerSecond(60, 60))
    }

    @Test
    fun otherRatesKeepTheirAverage() {
        assertEquals(45, drawsPerSecond(45, 60))
        assertEquals(90, drawsPerSecond(90, 120))
        assertEquals(100, drawsPerSecond(100, 120))
        assertEquals(37, drawsPerSecond(37, 60))
    }

    @Test
    fun ratesAboveTheRefreshRateDrawEveryFrame() {
        assertEquals(60, drawsPerSecond(144, 60))
        assertEquals(120, drawsPerSecond(0, 120))
    }

    @Test
    fun panelRatePrefersWholeMultiples() {
        val modes = intArrayOf(60, 90, 120)
        assertEquals(120, FrameRates.panelRate(modes, 0))
        assertEquals(90, FrameRates.panelRate(modes, 45))
        assertEquals(60, FrameRates.panelRate(modes, 30))
        assertEquals(60, FrameRates.panelRate(modes, 50))
        assertEquals(120, FrameRates.panelRate(modes, 100))
        assertEquals(120, FrameRates.panelRate(modes, 240))
    }

    @Test
    fun optionsStopAtTheFastestMode() {
        assertArrayEquals(
            intArrayOf(24, 30, 40, 45, 48, 50, 60, 72, 75, 90, 96, 100, 120),
            FrameRates.options(intArrayOf(60, 90, 120)),
        )
        assertArrayEquals(intArrayOf(24, 30, 40, 45, 48, 50, 57, 60), FrameRates.options(intArrayOf(57, 60)))
    }
}
