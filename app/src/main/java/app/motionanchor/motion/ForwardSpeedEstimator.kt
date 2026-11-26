// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import kotlin.math.exp

/**
 * Signed speed of the vehicle along the direction the user faces, in m/s: positive while
 * driving forward, negative while reversing.
 *
 * Integrating the longitudinal acceleration gives the sign and reacts instantly, but drifts.
 * Location fixes give a trustworthy magnitude but no direction and arrive late, so while they
 * are fresh the estimate is pulled toward the fix speed, signed by what the integration says.
 * Reversing is only believed at low speeds and after clear backward acceleration, so drift can
 * never flip a normal drive. Without fixes the estimate slowly leaks back to zero.
 */
internal class ForwardSpeedEstimator {
    var speed = 0f
        private set

    private var target: Float? = null
    private var targetAtMs = 0L

    /** Adds [accel] (m/s², positive forward) over [dt] seconds. */
    fun integrate(accel: Float, dt: Float, nowMs: Long) {
        speed += accel * dt
        val fix = target
        if (fix != null && nowMs - targetAtMs <= FIX_FRESH_MS) {
            speed += (fix - speed) * (dt / (FIX_BLEND_SEC + dt))
        } else {
            target = null
            speed *= exp(-dt / LEAK_SEC)
        }
        speed = speed.coerceIn(-MAX_SPEED, MAX_SPEED)
    }

    /** A location fix measured [metersPerSecond]; null when it carried no usable speed. */
    fun onLocationSpeed(metersPerSecond: Float?, nowMs: Long) {
        val magnitude = metersPerSecond ?: return
        val reversing = speed < -REVERSE_EVIDENCE && magnitude <= REVERSE_MAX_SPEED
        target = if (reversing) -magnitude else magnitude
        targetAtMs = nowMs
    }

    fun reset() {
        speed = 0f
        target = null
    }

    private companion object {
        const val FIX_FRESH_MS = 4_000L
        const val FIX_BLEND_SEC = 1.5f
        // Without location fixes, a drive at constant speed is indistinguishable from standing
        // still, so the inertial estimate fades after a while instead of drifting forever.
        const val LEAK_SEC = 12f
        const val REVERSE_EVIDENCE = 0.3f
        // 18 km/h: faster than any car moves in reverse.
        const val REVERSE_MAX_SPEED = 5f
        const val MAX_SPEED = 90f
    }
}
