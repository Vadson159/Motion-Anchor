// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Horizontal-world acceleration in m/s^2, plus device roll and angular rates, so the renderer
 * can reflect both translational and rotational motion of the phone through the world.
 *
 * [x], [y] are the screen-plane components of world-horizontal acceleration (+x right, +y up in
 * the device frame). [outOfPlane] is the signed component along the device's +Z axis (out of
 * the screen, toward the user). [rollRadians] is the angle of world-up projected onto the
 * screen — zero when upright portrait, ±π/2 when rotated to landscape.
 *
 * [yawRateRps] is the angular velocity around the world-vertical axis (rad/s). [pitchRateRps]
 * is the angular velocity around the world-horizontal axis perpendicular to the direction the
 * screen is facing. Together they describe how the phone's viewing direction rotates through
 * the world, driving the horizontal and vertical scroll of the dot grid.
 *
 * [forwardSpeed] is the signed travel speed (m/s) along the horizontal direction the user faces
 * through the screen: positive while moving forward, negative while reversing.
 *
 * One instance is reused for every sensor event, so it is only valid during the callback.
 */
class MotionVector {
    var x = 0f
    var y = 0f
    var outOfPlane = 0f
    var rollRadians = 0f
    var yawRateRps = 0f
    var pitchRateRps = 0f
    var forwardSpeed = 0f
}

/**
 * Fuses linear-acceleration, rotation-vector, and gyroscope data into the inputs the renderer
 * needs to make the dot field feel like a fixed world you're moving and rotating through.
 */
@Suppress("DEPRECATION")
class MotionEstimator(context: Context, private val onMotion: (MotionVector) -> Unit) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    // Only world-up and rotation about it are used, both independent of compass heading. The game
    // rotation vector leaves the magnetometer off, which saves power and ignores the magnetic
    // disturbances common inside vehicles. Fall back to the geomagnetic one where it is missing.
    private val rotationVector =
        (if (Build.VERSION.SDK_INT >= 18) sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) else null)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationType = rotationVector?.type ?: -1
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val motion = MotionVector()
    // Display.getRotation() can be a binder call to system_server (before Android 11), so it is
    // sampled a few times per second instead of on every sensor event.
    private var displayRotation = Surface.ROTATION_0
    private var rotationCheckedAtMs = 0L

    private val rotationMatrix = FloatArray(9)
    private val gravity = FloatArray(3)
    private var hasRotation = false
    private var hasGravity = false
    private var useRawAcceleration = false
    private var useGravityOrientation = false

    private var filteredX = 0f
    private var filteredY = 0f
    private var filteredZ = 0f
    private var lastRollRadians = 0f
    private var filteredYawRate = 0f
    private var filteredPitchRate = 0f
    private var lastAccelTsNs = 0L
    private var lastGyroTsNs = 0L

    // Gyro-bias state. Even "calibrated" gyros carry a small DC offset on each axis; with our
    // direct angular-rate-to-offset integration that bias becomes a perpetual scroll when the
    // phone is stationary. Bias is a hardware property of each device-frame axis, so we track
    // it in device coords and subtract *before* rotating to world/yaw/pitch.
    private var gxBias = 0f
    private var gyBias = 0f
    private var gzBias = 0f
    private var stillAccumSec = 0f
    private var lastAccelMagSq = 0f

    private val forward = ForwardSpeedEstimator()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> updateGravity(event.values, event.timestamp)
                rotationType -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    hasRotation = true
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (!hasRotation && !hasGravity) return
                    updateAccel(event.values[0], event.values[1], event.values[2], event.timestamp)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    if (!hasRotation && !hasGravity) return
                    updateGyro(event.values[0], event.values[1], event.values[2], event.timestamp)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun updateGravity(values: FloatArray, time: Long) {
        if (!hasGravity) {
            System.arraycopy(values, 0, gravity, 0, gravity.size)
            hasGravity = true
        } else {
            for (i in gravity.indices) gravity[i] += 0.025f * (values[i] - gravity[i])
        }
        if (useGravityOrientation) {
            val length = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
            if (length < 1f) return
            for (i in gravity.indices) rotationMatrix[6 + i] = gravity[i] / length
        }
        if (useRawAcceleration && (hasRotation || useGravityOrientation)) {
            updateAccel(values[0] - gravity[0], values[1] - gravity[1], values[2] - gravity[2], time)
        }
    }

    private fun updateAccel(ax: Float, ay: Float, az: Float, tsNs: Long) {
        // Android convention: getRotationMatrixFromVector gives R such that world = R * device.
        // The device-frame representation of world-up (0,0,1) is R^T * (0,0,1) = row 2 of R.
        val ux = rotationMatrix[6]
        val uy = rotationMatrix[7]
        val uz = rotationMatrix[8]

        val dot = ax * ux + ay * uy + az * uz
        val hx = ax - dot * ux
        val hy = ay - dot * uy
        val hz = az - dot * uz

        val dt = if (lastAccelTsNs == 0L) 0.02f else ((tsNs - lastAccelTsNs) / 1e9f).coerceIn(0.001f, 0.2f)
        lastAccelTsNs = tsNs
        val alpha = dt / (ACCEL_TIME_CONSTANT_SEC + dt)
        filteredX += alpha * (hx - filteredX)
        filteredY += alpha * (hy - filteredY)
        filteredZ += alpha * (hz - filteredZ)

        // Cache the current linear-accel magnitude squared for stillness detection in the
        // gyro handler (it typically ticks faster than accel).
        lastAccelMagSq = hx * hx + hy * hy + hz * hz

        forward.integrate(forwardComponent(hx, hy, hz, ux, uy, uz), dt, SystemClock.elapsedRealtime())

        val projLenSq = ux * ux + uy * uy
        if (projLenSq > FLAT_ROLL_GUARD_SQ) {
            lastRollRadians = atan2(ux, uy)
        }

        publish()
    }

    /**
     * The part of the horizontal acceleration (hx, hy, hz) along the horizontal direction the
     * user looks through the screen. Held upright that is straight into the screen (device -Z);
     * lying flat it is toward the top edge of the screen. In between both are blended, weighted
     * so each one fades out exactly where it degenerates.
     */
    private fun forwardComponent(hx: Float, hy: Float, hz: Float, ux: Float, uy: Float, uz: Float): Float {
        // Screen-up in device coordinates for the current display rotation.
        val (sx, sy) = when (displayRotation) {
            Surface.ROTATION_90 -> -1f to 0f
            Surface.ROTATION_180 -> 0f to -1f
            Surface.ROTATION_270 -> 1f to 0f
            else -> 0f to 1f
        }
        val sUp = sx * ux + sy * uy
        val weight = uz * uz
        // horizontal(-Z) + uz² · horizontal(screen-up)
        val fx = uz * ux + weight * (sx - sUp * ux)
        val fy = uz * uy + weight * (sy - sUp * uy)
        val fz = uz * uz - 1f + weight * (-sUp * uz)
        val lenSq = fx * fx + fy * fy + fz * fz
        if (lenSq < 1e-4f) return 0f
        return (hx * fx + hy * fy + hz * fz) / sqrt(lenSq)
    }

    /** Speed from a location fix, which corrects the drift of the integrated acceleration. */
    fun onLocationSpeed(metersPerSecond: Float?) {
        forward.onLocationSpeed(metersPerSecond, SystemClock.elapsedRealtime())
    }

    private fun updateGyro(gx: Float, gy: Float, gz: Float, tsNs: Long) {
        val dt = if (lastGyroTsNs == 0L) 0.01f else ((tsNs - lastGyroTsNs) / 1e9f).coerceIn(0.001f, 0.2f)
        lastGyroTsNs = tsNs

        // Stillness check uses raw device-frame gyro magnitude — orientation-invariant, so
        // "phone sitting flat on a table" and "phone upright on a shelf" are detected alike.
        val rawGyroMagSq = gx * gx + gy * gy + gz * gz
        val isStill = lastAccelMagSq < STILL_ACCEL_MAG_SQ && rawGyroMagSq < STILL_GYRO_MAG_SQ
        if (isStill) {
            stillAccumSec += dt
            if (stillAccumSec > STILL_SETTLE_SEC) {
                val biasAlpha = dt / (BIAS_TRACK_SEC + dt)
                gxBias += biasAlpha * (gx - gxBias)
                gyBias += biasAlpha * (gy - gyBias)
                gzBias += biasAlpha * (gz - gzBias)
            }
        } else {
            stillAccumSec = 0f
        }

        // De-bias in device frame, then rotate to world.
        val gxd = gx - gxBias
        val gyd = gy - gyBias
        val gzd = gz - gzBias
        val owx = rotationMatrix[0] * gxd + rotationMatrix[1] * gyd + rotationMatrix[2] * gzd
        val owy = rotationMatrix[3] * gxd + rotationMatrix[4] * gyd + rotationMatrix[5] * gzd
        val owz = rotationMatrix[6] * gxd + rotationMatrix[7] * gyd + rotationMatrix[8] * gzd

        val yawRate = owz

        val fx = -rotationMatrix[2]
        val fy = -rotationMatrix[5]
        val fLenSq = fx * fx + fy * fy
        val pitchRate = if (hasRotation && fLenSq > FLAT_PITCH_GUARD_SQ) {
            val invLen = 1f / sqrt(fLenSq)
            val sideX = -fy * invLen
            val sideY = fx * invLen
            owx * sideX + owy * sideY
        } else {
            0f // phone near flat — pitch axis is undefined, treat as zero
        }

        val alpha = dt / (GYRO_TIME_CONSTANT_SEC + dt)
        filteredYawRate += alpha * (yawRate - filteredYawRate)
        filteredPitchRate += alpha * (pitchRate - filteredPitchRate)

        publish()
    }

    private fun deadband(v: Float, threshold: Float): Float =
        if (v > threshold) v - threshold else if (v < -threshold) v + threshold else 0f

    private fun publish() {
        val now = SystemClock.uptimeMillis()
        if (now - rotationCheckedAtMs >= ROTATION_POLL_MS) {
            displayRotation = display.rotation
            rotationCheckedAtMs = now
        }
        var x = filteredX
        var y = filteredY
        var roll = lastRollRadians
        when (displayRotation) {
            Surface.ROTATION_0 -> Unit
            Surface.ROTATION_90 -> {
                x = filteredY
                y = -filteredX
                roll += HALF_TURN / 2f
            }
            Surface.ROTATION_180 -> {
                x = -filteredX
                y = -filteredY
                roll += HALF_TURN
            }
            Surface.ROTATION_270 -> {
                x = -filteredY
                y = filteredX
                roll -= HALF_TURN / 2f
            }
        }
        motion.x = x
        motion.y = y
        motion.outOfPlane = filteredZ
        motion.rollRadians = roll
        motion.yawRateRps = deadband(filteredYawRate, GYRO_DEADBAND_RPS)
        motion.pitchRateRps = deadband(filteredPitchRate, GYRO_DEADBAND_RPS)
        motion.forwardSpeed = forward.speed
        onMotion(motion)
    }

    fun start() {
        displayRotation = display.rotation
        rotationCheckedAtMs = SystemClock.uptimeMillis()
        useRawAcceleration = !register(linearAccel)
        useGravityOrientation = !register(rotationVector)
        if (useRawAcceleration || useGravityOrientation) register(accelerometer)
        register(gyroscope)
    }

    private fun register(sensor: Sensor?): Boolean =
        sensor != null && sensorManager.registerListener(listener, sensor, SAMPLING_PERIOD_US)

    fun stop() {
        sensorManager.unregisterListener(listener)
        hasRotation = false
        hasGravity = false
        lastAccelTsNs = 0L
        lastGyroTsNs = 0L
        filteredX = 0f
        filteredY = 0f
        filteredZ = 0f
        lastRollRadians = 0f
        filteredYawRate = 0f
        filteredPitchRate = 0f
        // Don't clear gx/gy/gzBias — bias is a hardware property; persist across stop/start.
        stillAccumSec = 0f
        lastAccelMagSq = 0f
        forward.reset()
        motion.x = 0f
        motion.y = 0f
        motion.outOfPlane = 0f
        motion.rollRadians = 0f
        motion.yawRateRps = 0f
        motion.pitchRateRps = 0f
        motion.forwardSpeed = 0f
        onMotion(motion)
    }

    companion object {
        private const val ROTATION_POLL_MS = 250L
        // 100 Hz: fast enough that roll and turn rates update between frames on 90/120 Hz
        // panels (SENSOR_DELAY_GAME is 50 Hz), and well below the 200 Hz limit that needs the
        // HIGH_SAMPLING_RATE_SENSORS permission.
        private const val SAMPLING_PERIOD_US = 10_000
        private const val HALF_TURN = 3.1415927f
        private const val ACCEL_TIME_CONSTANT_SEC = 0.08f
        private const val GYRO_TIME_CONSTANT_SEC = 0.03f

        private const val FLAT_ROLL_GUARD_SQ = 0.04f
        private const val FLAT_PITCH_GUARD_SQ = 0.04f

        // Stillness thresholds. Tight enough that actual motion doesn't count as still; loose
        // enough that hand-held-still does. ~0.3 m/s² accel magnitude, ~0.05 rad/s (3 deg/s)
        // rotation magnitude.
        private const val STILL_ACCEL_MAG_SQ = 0.09f
        private const val STILL_GYRO_MAG_SQ = 0.0025f
        // Require this much continuous stillness before starting to track bias.
        private const val STILL_SETTLE_SEC = 0.8f
        // How quickly the bias estimate converges while still — longer = steadier, slower.
        private const val BIAS_TRACK_SEC = 1.5f
        // Residual rate below this is rounded to zero to kill micro-drift.
        private const val GYRO_DEADBAND_RPS = 0.01f // ~0.57 deg/s
    }
}
