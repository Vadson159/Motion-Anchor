// SPDX-License-Identifier: GPL-3.0-only
package app.motionanchor.motion
/**
 * Uses a sustained GPS speed to separate a moving trip from isolated or noisy fixes.
 * A grace period keeps the overlay visible at traffic lights.
 */
internal class TravelDetector {
    private var fastSince: Long? = null
    private var lastFast: Long? = null
    private var previousFix: Long? = null

    fun update(now: Long, speedMetersPerSecond: Float?): Boolean {
        val alreadyMoving = isMoving(now)
        val previous = previousFix
        if (previous == null || now - previous > 10_000L) fastSince = null
        previousFix = now

        val speed = speedMetersPerSecond ?: 0f
        if (speed >= 5.5f) {
            val start = fastSince ?: now.also { fastSince = it }
            if (now - start >= 6_000L || alreadyMoving) lastFast = now
        } else {
            fastSince = null
            if (alreadyMoving && speed >= 3f) lastFast = now
        }
        return isMoving(now)
    }

    fun isMoving(now: Long): Boolean {
        val last = lastFast ?: return false
        if (now - last <= 120_000L) return true
        lastFast = null
        return false
    }
}
