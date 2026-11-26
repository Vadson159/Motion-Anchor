// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * HSV color palette: a saturation/brightness field for the current hue above a hue strip.
 * The field is drawn as three stacked fills (pure hue, white-to-clear, clear-to-black) instead of
 * a ComposeShader, which older hardware renderers cannot combine from two gradients.
 */
internal class ColorPickerView(context: Context) : View(context) {
    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 0f, 1f)
    private val density = resources.displayMetrics.density
    private val thumbRadius = 11f * density
    private val corner = 8f * density
    private val fieldRect = RectF()
    private val hueRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var whiteShader: Shader? = null
    private var blackShader: Shader? = null
    private var hueShader: Shader? = null
    private var dragging = NONE

    val color: Int get() = Color.HSVToColor(hsv)

    init {
        // A colored dot on a colored field is not readable for TalkBack; the hex field next to
        // the palette is the accessible input.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColor(value: Int) {
        Color.colorToHSV(value, hsv)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).takeIf {
            MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        } ?: (280 * density).toInt()
        val field = (width * 0.62f).coerceIn(150 * density, 230 * density)
        setMeasuredDimension(width, (field + (18 + 30) * density + 2 * thumbRadius).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val hueHeight = 30 * density
        fieldRect.set(thumbRadius, thumbRadius, w - thumbRadius, h - thumbRadius - hueHeight - 18 * density)
        hueRect.set(thumbRadius, h - thumbRadius - hueHeight, w - thumbRadius, h - thumbRadius)
        whiteShader = LinearGradient(fieldRect.left, 0f, fieldRect.right, 0f, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
        blackShader = LinearGradient(0f, fieldRect.top, 0f, fieldRect.bottom, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
        hueShader = LinearGradient(
            hueRect.left, 0f, hueRect.right, 0f,
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        fill.shader = null
        fill.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        canvas.drawRoundRect(fieldRect, corner, corner, fill)
        fill.shader = whiteShader
        canvas.drawRoundRect(fieldRect, corner, corner, fill)
        fill.shader = blackShader
        canvas.drawRoundRect(fieldRect, corner, corner, fill)
        fill.shader = hueShader
        canvas.drawRoundRect(hueRect, corner, corner, fill)
        fill.shader = null

        val fieldX = fieldRect.left + hsv[1] * fieldRect.width()
        val fieldY = fieldRect.top + (1f - hsv[2]) * fieldRect.height()
        drawThumb(canvas, fieldX, fieldY, color)
        val hueX = hueRect.left + hsv[0] / 360f * hueRect.width()
        drawThumb(canvas, hueX, hueRect.centerY(), Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
    }

    private fun drawThumb(canvas: Canvas, x: Float, y: Float, inner: Int) {
        fill.color = inner
        canvas.drawCircle(x, y, thumbRadius - 2 * density, fill)
        ring.strokeWidth = 3 * density
        ring.color = Color.WHITE
        canvas.drawCircle(x, y, thumbRadius - 2 * density, ring)
        ring.strokeWidth = 1 * density
        ring.color = 0x99000000.toInt()
        canvas.drawCircle(x, y, thumbRadius - 0.5f * density, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (event.y < (fieldRect.bottom + hueRect.top) / 2f) FIELD else HUE
                // Keep the dialog's scroll view from taking over a vertical drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                update(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> update(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        when (dragging) {
            FIELD -> {
                hsv[1] = ((x - fieldRect.left) / fieldRect.width()).coerceIn(0f, 1f)
                hsv[2] = 1f - ((y - fieldRect.top) / fieldRect.height()).coerceIn(0f, 1f)
            }
            HUE -> hsv[0] = ((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 360f
            else -> return
        }
        // 360° is the same red as 0°; keep it inside the range HSVToColor expects.
        if (hsv[0] >= 360f) hsv[0] = 359.9f
        invalidate()
        onColorChanged?.invoke(color)
    }

    private companion object {
        const val NONE = 0
        const val FIELD = 1
        const val HUE = 2
    }
}
