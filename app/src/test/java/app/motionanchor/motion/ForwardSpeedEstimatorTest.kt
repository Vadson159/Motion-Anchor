// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardSpeedEstimatorTest {
    private val estimator = ForwardSpeedEstimator()
    private var now = 0L

    /** Feeds [seconds] of 100 Hz acceleration, with a location fix every second if given. */
    private fun drive(seconds: Float, accel: Float, fix: Float? = null) {
        val steps = (seconds * 100).toInt()
        for (i in 0 until steps) {
            if (fix != null && now % 1_000L == 0L) estimator.onLocationSpeed(fix, now)
            estimator.integrate(accel, 0.01f, now)
            now += 10L
        }
    }

    @Test
    fun acceleratingForwardGivesPositiveSpeed() {
        drive(3f, 2f)
        assertTrue(estimator.speed > 4f)
    }

    @Test
    fun cruisingWithFixesKeepsForwardSpeed() {
        drive(5f, 2f, fix = 0f)
        drive(30f, 0f, fix = 15f)
        assertEquals(15f, estimator.speed, 0.5f)
    }

    @Test
    fun brakingWhileMovingForwardStaysForward() {
        drive(10f, 0f, fix = 15f)
        drive(3f, -4f, fix = 8f)
        assertTrue(estimator.speed > 0f)
    }

    @Test
    fun reversingFromAStopGivesNegativeSpeed() {
        drive(3f, 0f, fix = 0f)
        drive(1.5f, -1f, fix = 0f)
        drive(5f, 0f, fix = 1.5f)
        assertEquals(-1.5f, estimator.speed, 0.3f)
    }

    @Test
    fun driftCannotFlipAFastDriveIntoReverse() {
        drive(10f, 0f, fix = 15f)
        drive(5f, -3f, fix = 15f)
        drive(5f, 0f, fix = 15f)
        assertEquals(15f, estimator.speed, 0.5f)
    }

    @Test
    fun withoutFixesTheSpeedFadesAway() {
        drive(3f, 2f)
        drive(60f, 0f)
        assertEquals(0f, estimator.speed, 0.2f)
    }

    @Test
    fun startingMidDriveAssumesForward() {
        drive(5f, 0f, fix = 20f)
        assertTrue(estimator.speed > 15f)
    }
}
