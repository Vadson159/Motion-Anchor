// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor

import android.graphics.Color
import kotlin.math.min

/**
 * Interface colors derived from one theme color picked by the user. The theme color itself is
 * the accent (the main button and the status line); every surface, text, and border shade is a
 * darker or paler tone of its hue, so any color gives a consistent light, dark, or black theme.
 */
internal data class InterfacePalette(
    val platformTheme: Int,
    val page: Int,
    val panel: Int,
    val header: Int,
    val systemBars: Int,
    val text: Int,
    val secondary: Int,
    val border: Int,
    val button: Int,
    val pressed: Int,
    val disabled: Int,
    val accent: Int,
    val accentPressed: Int,
    val accentFocused: Int,
    val onAccent: Int,
    val headerText: Int,
    val headerAccent: Int,
    val control: Int,
    val previewLight: Int,
    val previewMuted: Int,
    val onPreviewLight: Int,
) {
    val isDark: Boolean get() = platformTheme != R.style.Theme_MotionAnchor

    companion object {
        // Saturation of the default mint accent; the tones below were tuned against it.
        private const val REFERENCE_SATURATION = 0.42f

        fun forTheme(theme: InterfaceTheme, seed: Int): InterfacePalette {
            val seedHsv = FloatArray(3)
            Color.colorToHSV(seed, seedHsv)
            val hue = seedHsv[0]
            val saturation = seedHsv[1]
            // Grey accents give a neutral theme, vivid ones a more colorful one.
            val k = min(saturation / REFERENCE_SATURATION, 1.9f)
            fun tone(s: Float, v: Float, cap: Float = 0.9f) =
                Color.HSVToColor(floatArrayOf(hue, (s * k).coerceIn(0f, cap), v))

            val lightHeader = tone(0.58f, 0.20f)
            val accentIsLight = luminance(seed) >= 0.45f
            val shared = Shared(
                accent = seed,
                accentPressed = Color.HSVToColor(floatArrayOf(hue, saturation, seedHsv[2] * 0.86f)),
                accentFocused = blend(seed, Color.WHITE, 0.3f),
                onAccent = if (accentIsLight) lightHeader else Color.WHITE,
                headerText = tone(0.06f, 0.91f),
                headerAccent = if (accentIsLight) seed else Color.HSVToColor(floatArrayOf(hue, min(saturation, 0.5f), 0.95f)),
                previewLight = tone(0.03f, 0.96f),
                previewMuted = tone(0.21f, 0.60f),
                onPreviewLight = lightHeader,
            )
            return when (theme) {
                InterfaceTheme.System, InterfaceTheme.Light -> shared.build(
                    platformTheme = R.style.Theme_MotionAnchor,
                    page = tone(0.02f, 0.965f),
                    panel = Color.WHITE,
                    header = lightHeader,
                    systemBars = lightHeader,
                    text = lightHeader,
                    secondary = tone(0.26f, 0.41f, 0.6f),
                    border = tone(0.05f, 0.89f),
                    button = tone(0.03f, 0.96f),
                    pressed = tone(0.17f, 0.91f, 0.45f),
                    disabled = tone(0.04f, 0.89f),
                    control = tone(0.82f, 0.40f, 0.95f),
                )
                InterfaceTheme.Dark -> shared.build(
                    platformTheme = R.style.Theme_MotionAnchor_Dark,
                    page = tone(0.48f, 0.106f),
                    panel = tone(0.41f, 0.153f),
                    header = tone(0.45f, 0.235f),
                    systemBars = tone(0.48f, 0.106f),
                    text = tone(0.035f, 0.96f),
                    secondary = tone(0.11f, 0.80f),
                    border = tone(0.26f, 0.34f),
                    button = tone(0.38f, 0.27f),
                    pressed = tone(0.36f, 0.40f),
                    disabled = tone(0.28f, 0.22f),
                    control = shared.headerAccent,
                )
                InterfaceTheme.Black -> shared.build(
                    platformTheme = R.style.Theme_MotionAnchor_Black,
                    page = Color.BLACK,
                    panel = tone(0.33f, 0.094f),
                    header = tone(0.54f, 0.16f),
                    systemBars = Color.BLACK,
                    text = tone(0.03f, 0.98f),
                    secondary = tone(0.09f, 0.82f),
                    border = tone(0.27f, 0.28f),
                    button = tone(0.37f, 0.20f),
                    pressed = tone(0.36f, 0.36f),
                    disabled = tone(0.23f, 0.17f),
                    control = shared.headerAccent,
                )
            }
        }

        /** Relative luminance (0 black … 1 white) of an sRGB color. */
        fun luminance(color: Int): Float {
            fun channel(c: Int): Float {
                val v = c / 255f
                return if (v <= 0.03928f) v / 12.92f else Math.pow((v + 0.055) / 1.055, 2.4).toFloat()
            }
            return 0.2126f * channel(Color.red(color)) + 0.7152f * channel(Color.green(color)) +
                0.0722f * channel(Color.blue(color))
        }

        private fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
        )
    }

    /** Colors that do not depend on the light, dark, or black variant. */
    private class Shared(
        val accent: Int,
        val accentPressed: Int,
        val accentFocused: Int,
        val onAccent: Int,
        val headerText: Int,
        val headerAccent: Int,
        val previewLight: Int,
        val previewMuted: Int,
        val onPreviewLight: Int,
    ) {
        fun build(
            platformTheme: Int, page: Int, panel: Int, header: Int, systemBars: Int, text: Int,
            secondary: Int, border: Int, button: Int, pressed: Int, disabled: Int, control: Int,
        ) = InterfacePalette(
            platformTheme, page, panel, header, systemBars, text, secondary, border, button, pressed,
            disabled, accent, accentPressed, accentFocused, onAccent, headerText, headerAccent, control,
            previewLight, previewMuted, onPreviewLight,
        )
    }
}
