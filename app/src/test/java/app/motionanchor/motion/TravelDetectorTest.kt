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
        assertFalse(detector.update(15_000L, 12f))
        assertFalse(detector.update(21_000L, null))
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
}
