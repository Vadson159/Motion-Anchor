// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.Choreographer
import android.view.View
import app.motionanchor.CueAppearance
import app.motionanchor.CueStyle
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the dot sizes scale with screen position.
 * - [Uniform]: every dot at the same base size, covering the whole screen.
 * - [Focus]: dots fade to zero near the center and reach full size at the edges/corners, so
 *   the middle of the screen stays clear for content and motion cues stay in peripheral vision.
 */
enum class CueMode { Uniform, Focus }

/**
 * Overlay view that renders the infinite dot field with a hardware-accelerated `Canvas`.
 *
 * Drawing through the View hierarchy (rather than a SurfaceView/GLSurfaceView) matters for more
 * than aesthetics: on Android 12+, only pixels that go through the window's compositor are
 * dimmed by `WindowManager.LayoutParams.alpha`. A SurfaceView renders into a separate
 * SurfaceFlinger layer at 100% opacity regardless of window alpha, so the OS still flags
 * touches as obscured and blocks them from reaching the app below. Canvas-drawn pixels are
 * part of the window and are dimmed correctly, which is what lets the touch-through trick
 * work.
 *
 * The physics runs in a "leveled" isotropic frame whose +y axis tracks world-up projected
 * onto the screen. Positions rotate to screen coords at draw time. One iso unit = half the
 * shorter screen dimension, so rotation is rigid in pixel space regardless of viewport aspect.
 *
 * When forward/backward motion is enabled, the field behaves like the ground the vehicle passes
 * over: while driving forward it flows steadily down the screen (toward the user), while reversing
 * it flows up, and it stands still when the vehicle stops. When it is disabled, acceleration
 * along both screen axes nudges the field directly and damps back to rest. Sideways acceleration
 * and turning shift it left or right, and the phone's own rotation scrolls it as if the phone were
 * looking through a fixed world.
 *
 * Every redraw makes the system recompose a full-screen layer, so the view only redraws when
 * the picture would move by at least [MIN_SHIFT_PX] and stops its frame loop entirely while the
 * field is at rest. While it moves it redraws at the frame rate chosen in the settings (by
 * default every vsync) and asks the panel for that refresh rate, so 90/120 Hz screens can show
 * the markers at their full frame rate.
 */
class CueOverlayView @JvmOverloads constructor(context: Context, private val preview: Boolean = false) : View(context) {

    private var motionX = 0f
    private var motionY = 0f
    private var motionOutOfPlane = 0f
    private var rollRadians = 0f
    private var yawRateRps = 0f
    private var pitchRateRps = 0f
    private var forwardSpeed = 0f

    private val pixelDensity = context.resources.displayMetrics.density

    /** Home x, home y, and base size of every marker, packed in threes. */
    private var particles = FloatArray(0)
    private var halfShortDim = 1f
    private var halfDiag = 1f
    private var centerX = 0f
    private var centerY = 0f

    private var appearance = CueAppearance()
    private var marker: Bitmap? = null
    private val imageRect = RectF()
    private val path = Path()

    private var gridVx = 0f
    private var gridVy = 0f
    private var gridOx = 0f
    private var gridOy = 0f
    private var sizeEnvelope = 0f

    // State shown by the last onDraw, to measure how far the picture has moved since.
    private var drawnOx = 0f
    private var drawnOy = 0f
    private var drawnRoll = 0f
    private var drawnBoost = 1f
    private var drawnHorizon = 0f

    private var lastFrameNs = 0L
    private val pacer = FramePacer()
    private var looping = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        isClickable = false
        isFocusable = false
        applyFrameRate()
    }

    private fun applyFrameRate() {
        val rate = appearance.frameRate
        pacer.setRate(rate)
        // The vote only counts while the view invalidates, so a resting field lets the panel
        // drop back to its idle refresh rate.
        if (!preview && Build.VERSION.SDK_INT >= 35) {
            requestedFrameRate = if (rate > 0) rate.toFloat() else REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    fun setAppearance(next: CueAppearance, image: Bitmap?) {
        if (appearance.density != next.density) particles = FloatArray(0)
        if (next.forwardMotion && !appearance.forwardMotion) gridVy = 0f
        appearance = next
        applyFrameRate()
        marker = image
        if (particles.isEmpty()) resetParticles()
        if (!looping && (pendingShift() >= MIN_SHIFT_PX || !isAtRest())) startLoop()
        invalidate()
    }

    fun setMotion(m: MotionVector) {
        motionX = m.x
        motionY = m.y
        motionOutOfPlane = m.outOfPlane
        rollRadians = m.rollRadians
        yawRateRps = m.yawRateRps
        pitchRateRps = m.pitchRateRps
        forwardSpeed = m.forwardSpeed
        if (!looping && (pendingShift() >= MIN_SHIFT_PX || !isAtRest())) startLoop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        halfShortDim = max(1f, min(w, h) / 2f)
        halfDiag = max(1f, sqrt((w.toFloat() * w + h.toFloat() * h)) / 2f)
        centerX = w / 2f
        centerY = h / 2f
        if (particles.isEmpty()) resetParticles()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (looping || preview || windowToken == null || windowVisibility != VISIBLE) return
        looping = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            val dt = if (lastFrameNs == 0L) 1f / 60f
                     else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
            lastFrameNs = frameTimeNanos
            if (appearance.style != CueStyle.Horizon) {
                step(dt)
                updateSizeEnvelope(dt)
            }
            val moved = pendingShift() >= MIN_SHIFT_PX
            if (moved && pacer.shouldDraw(frameTimeNanos)) invalidate()
            if (moved || !isAtRest()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                looping = false
            }
        }
    }

    /** Upper bound, in pixels, of how far any marker moved or grew since the last draw. */
    private fun pendingShift(): Float {
        val turn = abs(rollRadians - drawnRoll) * halfDiag
        if (appearance.style == CueStyle.Horizon) return turn + abs(horizonOffset() - drawnHorizon)
        return turn + (abs(gridOx - drawnOx) + abs(gridOy - drawnOy)) * halfShortDim +
            abs(sizeBoost() - drawnBoost) * maxMarkerExtent()
    }

    /**
     * True when the field would stay visibly still without new input: the grid has stopped,
     * the current acceleration, travel, and rotation would move it slower than [REST_SPEED_PX], and
     * out-of-plane motion is too weak to grow the markers. The horizon line has no inertia.
     */
    private fun isAtRest(): Boolean {
        if (appearance.style == CueStyle.Horizon) return true
        val sensitivity = appearance.sensitivity / 100f
        val glide = abs(gridVx) + abs(gridVy)
        val drive = (abs(motionX) + abs(motionY)) * DRIVE_GAIN * sensitivity / DAMP
        val turn = (abs(yawRateRps) * YAW_GAIN + abs(pitchRateRps) * PITCH_GAIN) * sensitivity
        val travel = if (appearance.forwardMotion) abs(travelFlow()) else 0f
        val growth = max(sizeEnvelope, max(0f, motionOutOfPlane) * SIZE_OUT_OF_PLANE_GAIN * sensitivity)
        return (glide + drive + turn + travel) * halfShortDim < REST_SPEED_PX && growth * maxMarkerExtent() < MIN_SHIFT_PX
    }

    private fun sizeBoost() = (1f + sizeEnvelope).coerceIn(1f, SIZE_BOOST_MAX)

    /** Largest marker half-extent before the size boost; images reach twice the dot radius. */
    private fun maxMarkerExtent() = 2f * DOT_SIZE_PX * 1.15f * pixelDensity * appearance.size / 200f

    private fun horizonOffset() =
        (-motionY * appearance.sensitivity / 100f * 0.04f).coerceIn(-0.2f, 0.2f) * halfShortDim

    override fun onDraw(canvas: Canvas) {
        drawnRoll = rollRadians
        paint.color = appearance.color
        paint.alpha = appearance.opacity * 255 / 100
        paint.style = Paint.Style.FILL
        if (appearance.style == CueStyle.Horizon) {
            drawnHorizon = horizonOffset()
            drawHorizon(canvas, drawnHorizon)
            return
        }
        drawnOx = gridOx
        drawnOy = gridOy
        val sizeBoost = sizeBoost()
        drawnBoost = sizeBoost
        val cosR = cos(rollRadians)
        val sinR = sin(rollRadians)
        val ext = GRID_EXTENT
        val span = 2f * ext
        val sizeScale = pixelDensity * sizeBoost * appearance.size / 200f

        val focusMode = appearance.placement == CueMode.Focus
        val layout = particles
        var i = 0
        while (i < layout.size) {
            // Apply global grid offset and wrap into the toroidal iso square.
            val lx = wrap(layout[i] + gridOx, ext, span)
            val ly = wrap(layout[i + 1] + gridOy, ext, span)
            var radius = layout[i + 2] * sizeScale
            i += 3
            // Rigid rotation in iso space.
            val sx = lx * cosR + ly * sinR
            val sy = -lx * sinR + ly * cosR
            // Iso → pixels via uniform scale by halfShortDim. Rigid rotation in iso becomes
            // rigid rotation in pixels. Y flipped (pixel +y is down; iso +y is up).
            val px = centerX + sx * halfShortDim
            val py = centerY - sy * halfShortDim

            if (focusMode) {
                val dx = px - centerX
                val dy = py - centerY
                // Normalize by half-diagonal → 0 at center, 1 at corners. Smoothstep gives a
                // clear empty zone in the middle and full-size dots only near the edges.
                val distNorm = sqrt(dx * dx + dy * dy) / halfDiag
                val focus = smoothstep(FOCUS_INNER, FOCUS_OUTER, distNorm)
                if (focus <= 0.01f) continue
                radius *= focus
            }
            drawMarker(canvas, px, py, radius)
        }
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, radius: Float) {
        when (appearance.style) {
            CueStyle.Dots -> canvas.drawCircle(x, y, radius, paint)
            CueStyle.Rings -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = radius * 0.35f
                canvas.drawCircle(x, y, radius, paint)
                paint.style = Paint.Style.FILL
            }
            CueStyle.Diamonds -> drawPolygon(canvas, x, y, radius, DIAMOND)
            CueStyle.Stars -> drawPolygon(canvas, x, y, radius * 1.3f, STAR)
            CueStyle.Image -> {
                val bitmap = marker
                if (bitmap == null) {
                    canvas.drawCircle(x, y, radius, paint)
                    return
                }
                val scale = radius * 2f / max(bitmap.width, bitmap.height)
                val halfW = bitmap.width * scale
                val halfH = bitmap.height * scale
                imageRect.set(x - halfW, y - halfH, x + halfW, y + halfH)
                canvas.drawBitmap(bitmap, null, imageRect, paint)
            }
            CueStyle.Horizon -> Unit
        }
    }

    private fun drawPolygon(canvas: Canvas, x: Float, y: Float, radius: Float, outline: FloatArray) {
        path.rewind()
        path.moveTo(x + radius * outline[0], y + radius * outline[1])
        var i = 2
        while (i < outline.size) {
            path.lineTo(x + radius * outline[i], y + radius * outline[i + 1])
            i += 2
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawHorizon(canvas: Canvas, offset: Float) {
        val angle = Math.toDegrees(rollRadians.toDouble()).toFloat()
        val gap = if (appearance.placement == CueMode.Focus) halfShortDim * 0.6f else 0f
        val length = halfDiag * 1.04f
        paint.strokeWidth = 3f * pixelDensity * appearance.size / 100f
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(angle)
        canvas.drawLine(-length, offset, -gap, offset, paint)
        canvas.drawLine(gap, offset, length, offset, paint)
        canvas.restore()
    }

    private fun step(dt: Float) {
        val cosR = cos(rollRadians)
        val sinR = sin(rollRadians)
        val sensitivity = appearance.sensitivity / 100f
        val damping = exp(-DAMP * dt)

        val driveLx = motionX * cosR - motionY * sinR
        gridVx += (-driveLx * DRIVE_GAIN) * sensitivity * dt
        gridVx *= damping
        gridOx += gridVx * dt

        if (appearance.forwardMotion) {
            // Forward travel moves the markers down the screen, reversing moves them up.
            // Lengthwise acceleration is omitted here so braking while still moving forward
            // does not push the markers backward.
            gridVy = 0f
            gridOy -= travelFlow() * dt
        } else {
            val driveLy = motionX * sinR + motionY * cosR
            gridVy += (-driveLy * DRIVE_GAIN) * sensitivity * dt
            gridVy *= damping
            gridOy += gridVy * dt
        }

        // Rotation scrolls the grid directly — sustained rotation → sustained flow,
        // stop rotating → flow stops.
        gridOx += yawRateRps * YAW_GAIN * sensitivity * dt
        gridOy += pitchRateRps * PITCH_GAIN * sensitivity * dt

        val wrapSpan = 2f * GRID_EXTENT
        if (gridOx > GRID_EXTENT) gridOx -= wrapSpan
        else if (gridOx < -GRID_EXTENT) gridOx += wrapSpan
        if (gridOy > GRID_EXTENT) gridOy -= wrapSpan
        else if (gridOy < -GRID_EXTENT) gridOy += wrapSpan
    }

    /**
     * Scroll speed of the field in iso units per second for the current travel speed: grows
     * with speed and levels off toward [FLOW_MAX] so highway speeds stay readable.
     */
    private fun travelFlow(): Float {
        val speed = abs(forwardSpeed)
        if (speed < FLOW_DEADBAND) return 0f
        val moving = speed - FLOW_DEADBAND
        val flow = FLOW_MAX * moving / (moving + FLOW_HALF_SPEED) * appearance.sensitivity / 100f
        return if (forwardSpeed > 0f) flow else -flow
    }

    private fun updateSizeEnvelope(dt: Float) {
        val target = max(0f, motionOutOfPlane) * SIZE_OUT_OF_PLANE_GAIN * appearance.sensitivity / 100f
        if (target > sizeEnvelope) sizeEnvelope = target
        sizeEnvelope *= exp(-dt / SIZE_RELEASE_SEC)
    }

    private fun resetParticles() {
        val rng = Random(42)
        val dimension = (GRID_DIM * sqrt(appearance.density / 100f)).roundToInt()
        val layout = FloatArray(dimension * dimension * 3)
        val spacing = 2f * GRID_EXTENT / dimension
        var i = 0
        // Staggered layout: odd rows shifted by half a column → hex-like pattern.
        for (row in 0 until dimension) {
            val rowOffsetX = if (row % 2 == 1) spacing * 0.5f else 0f
            for (col in 0 until dimension) {
                val nx = (col + 0.5f) * spacing - GRID_EXTENT + rowOffsetX
                val ny = (row + 0.5f) * spacing - GRID_EXTENT
                val jitterX = (rng.nextFloat() - 0.5f) * 0.04f
                val jitterY = (rng.nextFloat() - 0.5f) * 0.04f
                layout[i++] = nx + jitterX
                layout[i++] = ny + jitterY
                layout[i++] = DOT_SIZE_PX * (0.85f + rng.nextFloat() * 0.3f)
            }
        }
        particles = layout
    }

    companion object {
        private const val GRID_DIM = 12
        private const val GRID_EXTENT = 3.5f

        private const val DRIVE_GAIN = 0.6f
        private const val DAMP = 1.2f
        private const val YAW_GAIN = 1.5f
        private const val PITCH_GAIN = 1.5f
        private const val DOT_SIZE_PX = 8f

        // Travel flow: iso units per second approached at high speed, the speed (m/s) at which
        // half of it is reached, and the speed below which the field stands still.
        private const val FLOW_MAX = 1.6f
        private const val FLOW_HALF_SPEED = 10f
        private const val FLOW_DEADBAND = 0.4f

        private const val SIZE_OUT_OF_PLANE_GAIN = 1.2f
        private const val SIZE_RELEASE_SEC = 0.9f
        private const val SIZE_BOOST_MAX = 8f

        // Focus-mode radial falloff: below FOCUS_INNER (normalized distance from center,
        // 0=center 1=corner) dots are invisible; above FOCUS_OUTER they're full size.
        private const val FOCUS_INNER = 0.25f
        private const val FOCUS_OUTER = 0.9f

        // Changes smaller than this are not drawn; they are barely visible on an antialiased
        // marker. Kept small so slow drifts still move on every frame at 120 Hz.
        private const val MIN_SHIFT_PX = 0.25f
        // Below this speed the field counts as stopped and the frame loop ends.
        private const val REST_SPEED_PX = 0.5f

        // Unit outlines (radius 1), precomputed so drawing needs no trigonometry per vertex.
        private val DIAMOND = outline(4, 1f)
        private val STAR = outline(10, 0.44f)

        private fun outline(vertices: Int, inset: Float) = FloatArray(vertices * 2).also { points ->
            for (i in 0 until vertices) {
                val angle = (2 * Math.PI * i / vertices - Math.PI / 2).toFloat()
                val r = if (i % 2 == 0) 1f else inset
                points[2 * i] = r * cos(angle)
                points[2 * i + 1] = r * sin(angle)
            }
        }

        private fun wrap(v: Float, ext: Float, span: Float): Float {
            var r = (v + ext) % span
            if (r < 0f) r += span
            return r - ext
        }

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
