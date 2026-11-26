// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/** Frame rates for the overlay and the refresh rates the built-in display offers. */
object FrameRates {
    /** Highest rate the custom field accepts. */
    const val CUSTOM_MAX = 480

    // Film, PAL, and the usual panel steps, offered next to the display's own modes.
    private val COMMON = intArrayOf(24, 30, 40, 45, 48, 50, 60, 72, 75, 90, 96, 100, 120, 144, 165, 180, 240)

    /** Refresh rates of the display at its current resolution, slowest first. */
    @Suppress("DEPRECATION")
    fun supported(context: Context): IntArray {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val rates = if (Build.VERSION.SDK_INT >= 23) {
            val current = display.mode
            display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .map { it.refreshRate.roundToInt() }
        } else {
            listOf(display.refreshRate.roundToInt())
        }
        val result = rates.filter { it > 0 }.distinct().sorted()
        return if (result.isEmpty()) intArrayOf(60) else result.toIntArray()
    }

    /** Rates worth listing: the common ones the display can reach, plus its own modes. */
    fun options(native: IntArray): IntArray {
        val fastest = native.last()
        return (COMMON.filter { it <= fastest } + native.toList()).distinct().sorted().toIntArray()
    }

    /** The refresh rate the overlay asks the panel for. */
    fun target(context: Context, chosen: Int): Int = panelRate(supported(context), chosen)

    /**
     * The panel mode for [chosen] frames per second: the fastest mode when it is 0, otherwise the
     * slowest mode that shows every frame for the same number of refreshes (90 Hz for 45 fps), and
     * failing that the slowest mode that is still fast enough.
     */
    fun panelRate(native: IntArray, chosen: Int): Int {
        if (chosen <= 0) return native.last()
        native.firstOrNull { it >= chosen && isMultiple(it, chosen) }?.let { return it }
        return native.firstOrNull { it >= chosen } ?: native.last()
    }

    private fun isMultiple(rate: Int, chosen: Int): Boolean {
        val times = (rate.toFloat() / chosen).roundToInt()
        return times >= 1 && abs(rate - times * chosen) <= 1
    }
}

/**
 * Decides which vsync frames to draw so the overlay runs at a chosen rate. Due times advance by
 * exactly one interval, so a rate that does not divide the refresh rate (45 fps on 60 Hz) keeps
 * its average instead of falling to the next divisor (30 fps).
 */
internal class FramePacer {
    private var intervalNs = 0L
    private var dueNs = 0L

    /** 0 or less draws every frame. */
    fun setRate(framesPerSecond: Int) {
        val next = if (framesPerSecond > 0) 1_000_000_000L / framesPerSecond else 0L
        if (next != intervalNs) {
            intervalNs = next
            dueNs = 0L
        }
    }

    fun shouldDraw(frameTimeNanos: Long): Boolean {
        val interval = intervalNs
        if (interval <= 0L) return true
        // Vsync timestamps jitter; without slack 60 fps on a 120 Hz panel would skip due frames.
        if (frameTimeNanos < dueNs - SLACK_NS) return false
        // After a pause (the field was at rest) start a fresh schedule instead of catching up.
        dueNs = if (frameTimeNanos - dueNs > interval) frameTimeNanos + interval else dueNs + interval
        return true
    }

    private companion object {
        const val SLACK_NS = 1_500_000L
    }
}
