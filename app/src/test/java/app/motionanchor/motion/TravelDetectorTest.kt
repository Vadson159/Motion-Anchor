// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelDetectorTest {
    @Test
    fun isolatedGpsFixDoesNotStartOverlay() {
        val detector = TravelDetector()
        assertFalse(detector.update(0L, 12f))
        assertFalse(detector.update(25_000L, 12f))
        assertFalse(detector.update(31_000L, null))
    }

    @Test
    fun sustainedMotionStartsAndBriefStopsKeepOverlayVisible() {
        val detector = TravelDetector()
        assertFalse(detector.update(0L, 8f))
        assertFalse(detector.update(3_000L, 8f))
        assertTrue(detector.update(6_000L, 8f))
        assertTrue(detector.update(25_000L, 0f))
        assertTrue(detector.isMoving(126_000L))
        assertFalse(detector.isMoving(126_001L))
    }

    @Test
    fun firstFixAfterExpiredSessionNeedsFreshConfirmation() {
        val detector = TravelDetector()
        detector.update(0L, 10f)
        detector.update(6_000L, 10f)
        assertFalse(detector.update(150_000L, 10f))
        assertTrue(detector.update(156_000L, 10f))
    }

    @Test
    fun fixesWithoutSpeedDoNotBreakTheStreak() {
        val detector = TravelDetector()
        assertFalse(detector.update(0L, 9f))
        assertFalse(detector.update(1_000L, null))
        assertFalse(detector.update(2_000L, null))
        assertTrue(detector.update(5_000L, 9f))
    }

    @Test
    fun sparseNetworkFixesStartATrip() {
        val detector = TravelDetector()
        assertFalse(detector.update(0L, 11f))
        assertTrue(detector.update(15_000L, 11f))
    }

    @Test
    fun walkingDoesNotStartATrip() {
        val detector = TravelDetector()
        for (second in 0..60) assertFalse(detector.update(second * 1_000L, 1.6f))
    }
}
