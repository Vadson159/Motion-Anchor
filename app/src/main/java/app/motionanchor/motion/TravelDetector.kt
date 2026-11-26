// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only
package app.motionanchor.motion

/**
 * Uses a sustained location speed to separate a moving trip from isolated or noisy fixes.
 * A grace period keeps the overlay visible at traffic lights and stations.
 *
 * Fixes may come from GPS (one per second) or from the network provider (every few seconds),
 * so two fast fixes up to [MAX_GAP_MS] apart still count as one streak. A fix whose speed is
 * unknown neither starts nor ends a trip: inside a bus or train GPS often reports positions
 * without a usable speed, and treating those as "stopped" kept the overlay from ever starting.
 */
internal class TravelDetector {
    private var fastSince: Long? = null
    private var lastFast: Long? = null
    private var previousFix: Long? = null

    fun update(now: Long, speedMetersPerSecond: Float?): Boolean {
        val speed = speedMetersPerSecond ?: return isMoving(now)
        val alreadyMoving = isMoving(now)
        val previous = previousFix
        if (previous == null || now - previous > MAX_GAP_MS) fastSince = null
        previousFix = now

        if (speed >= START_SPEED) {
            val start = fastSince ?: now.also { fastSince = it }
            if (now - start >= CONFIRM_MS || alreadyMoving) lastFast = now
        } else {
            fastSince = null
            if (alreadyMoving && speed >= KEEP_SPEED) lastFast = now
        }
        return isMoving(now)
    }

    fun isMoving(now: Long): Boolean {
        val last = lastFast ?: return false
        if (now - last <= GRACE_MS) return true
        lastFast = null
        return false
    }

    private companion object {
        // 18 km/h: faster than running, slow enough for a city bus between stops.
        const val START_SPEED = 5f
        const val KEEP_SPEED = 2.5f
        const val CONFIRM_MS = 5_000L
        const val MAX_GAP_MS = 20_000L
        const val GRACE_MS = 120_000L
    }
}
