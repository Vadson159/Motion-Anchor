// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import app.motionanchor.CueAppearance
import app.motionanchor.CueStyle
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

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
 */
class CueOverlayView @JvmOverloads constructor(context: Context, private val preview: Boolean = false) : View(context) {

    @Volatile private var motionX = 0f
    @Volatile private var motionY = 0f
    @Volatile private var motionOutOfPlane = 0f
    @Volatile private var rollRadians = 0f
    @Volatile private var yawRateRps = 0f
    @Volatile private var pitchRateRps = 0f

    private val pixelDensity = context.resources.displayMetrics.density

    private var particles = emptyArray<Particle>()
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

    private var lastFrameNs = 0L
    private var running = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        setBackgroundColor(0)
        isClickable = false
        isFocusable = false
    }

    fun setAppearance(next: CueAppearance, image: Bitmap?) {
        if (appearance.density != next.density) particles = emptyArray()
        appearance = next
        marker = image
        if (particles.isEmpty()) resetParticles()
        invalidate()
    }

    fun setMotion(m: MotionVector) {
        motionX = m.x
        motionY = m.y
        motionOutOfPlane = m.outOfPlane
        rollRadians = m.rollRadians
        yawRateRps = m.yawRateRps
        pitchRateRps = m.pitchRateRps
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
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        val shouldRun = !preview && windowVisibility == VISIBLE && windowToken != null
        if (shouldRun == running) return
        running = shouldRun
        if (running) {
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 1f / 60f
                     else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
            lastFrameNs = frameTimeNanos
            step(dt)
            updateSizeEnvelope(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = appearance.color
        paint.alpha = appearance.opacity * 255 / 100
        paint.style = Paint.Style.FILL
        if (appearance.style == CueStyle.Horizon) {
            drawHorizon(canvas)
            return
        }
        val sizeBoost = (1f + sizeEnvelope).coerceIn(1f, SIZE_BOOST_MAX)
        val cosR = cos(rollRadians)
        val sinR = sin(rollRadians)
        val ext = GRID_EXTENT
        val span = 2f * ext

        val focusMode = appearance.placement == CueMode.Focus
        for (p in particles) {
            // Apply global grid offset and wrap into the toroidal iso square.
            val lx = wrap(p.homeX + gridOx, ext, span)
            val ly = wrap(p.homeY + gridOy, ext, span)
            // Rigid rotation in iso space.
            val sx = lx * cosR + ly * sinR
            val sy = -lx * sinR + ly * cosR
            // Iso → pixels via uniform scale by halfShortDim. Rigid rotation in iso becomes
            // rigid rotation in pixels. Y flipped (pixel +y is down; iso +y is up).
            val px = centerX + sx * halfShortDim
            val py = centerY - sy * halfShortDim

            var radius = p.size * pixelDensity * sizeBoost * appearance.size / 200f
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
            CueStyle.Diamonds -> drawPolygon(canvas, x, y, radius, 4, 1f)
            CueStyle.Stars -> drawPolygon(canvas, x, y, radius * 1.3f, 10, 0.44f)
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

    private fun drawPolygon(canvas: Canvas, x: Float, y: Float, radius: Float, vertices: Int, inset: Float) {
        path.rewind()
        for (i in 0 until vertices) {
            val angle = (2 * Math.PI * i / vertices - Math.PI / 2).toFloat()
            val r = if (i % 2 == 0) radius else radius * inset
            val px = x + r * cos(angle)
            val py = y + r * sin(angle)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawHorizon(canvas: Canvas) {
        val angle = Math.toDegrees(rollRadians.toDouble()).toFloat()
        val offset = (-motionY * appearance.sensitivity / 100f * 0.04f)
            .coerceIn(-0.2f, 0.2f) * halfShortDim
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
        val driveLx = motionX * cosR - motionY * sinR
        val driveLy = motionX * sinR + motionY * cosR

        val sensitivity = appearance.sensitivity / 100f
        gridVx += (-driveLx * DRIVE_GAIN) * sensitivity * dt
        gridVy += (-driveLy * DRIVE_GAIN) * sensitivity * dt
        val damping = exp(-DAMP * dt)
        gridVx *= damping
        gridVy *= damping
        gridOx += gridVx * dt
        gridOy += gridVy * dt

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

    private fun updateSizeEnvelope(dt: Float) {
        val target = max(0f, motionOutOfPlane) * SIZE_OUT_OF_PLANE_GAIN * appearance.sensitivity / 100f
        if (target > sizeEnvelope) sizeEnvelope = target
        sizeEnvelope *= exp(-dt / SIZE_RELEASE_SEC)
    }

    private fun resetParticles() {
        val rng = Random(42)
        val dimension = (GRID_DIM * sqrt(appearance.density / 100f)).roundToInt()
        particles = Array(dimension * dimension) { Particle() }
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
                val p = particles[i++]
                p.homeX = nx + jitterX
                p.homeY = ny + jitterY
                p.size = DOT_SIZE_PX * (0.85f + rng.nextFloat() * 0.3f)
            }
        }
    }

    private class Particle {
        var homeX = 0f
        var homeY = 0f
        var size = 0f
    }

    companion object {
        private const val GRID_DIM = 12
        private const val GRID_EXTENT = 3.5f

        private const val DRIVE_GAIN = 0.6f
        private const val DAMP = 1.2f
        private const val YAW_GAIN = 1.5f
        private const val PITCH_GAIN = 1.5f
        private const val DOT_SIZE_PX = 8f

        private const val SIZE_OUT_OF_PLANE_GAIN = 1.2f
        private const val SIZE_RELEASE_SEC = 0.9f
        private const val SIZE_BOOST_MAX = 8f

        // Focus-mode radial falloff: below FOCUS_INNER (normalized distance from center,
        // 0=center 1=corner) dots are invisible; above FOCUS_OUTER they're full size.
        private const val FOCUS_INNER = 0.25f
        private const val FOCUS_OUTER = 0.9f

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
