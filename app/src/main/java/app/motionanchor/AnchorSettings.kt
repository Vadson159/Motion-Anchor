// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import app.motionanchor.motion.CueMode
import java.util.Locale

enum class CueStyle(val title: Int) {
    Dots(R.string.style_dots),
    Rings(R.string.style_rings),
    Diamonds(R.string.style_diamonds),
    Stars(R.string.style_stars),
    Horizon(R.string.style_horizon),
    Image(R.string.style_image),
}

enum class InterfaceTheme(val title: Int) {
    System(R.string.theme_system),
    Light(R.string.theme_light),
    Dark(R.string.theme_dark),
    Black(R.string.theme_black),
}

data class CueAppearance(
    val style: CueStyle = CueStyle.Dots,
    val placement: CueMode = CueMode.Focus,
    val color: Int = Color.WHITE,
    val size: Int = 100,
    val opacity: Int = 85,
    val density: Int = 100,
    val sensitivity: Int = 100,
    /** Scrolls the marker field with forward and reverse travel instead of longitudinal acceleration alone. */
    val forwardMotion: Boolean = false,
    /** Highest frame rate of the overlay; 0 follows the fastest refresh rate of the display. */
    val frameRate: Int = 0,
)

class AnchorSettings(context: Context) {
    val preferences = context.getSharedPreferences("motion_anchor", Context.MODE_PRIVATE)

    val appearance: CueAppearance
        get() = CueAppearance(
            style = readEnum(STYLE, CueStyle.Dots),
            placement = readEnum(PLACEMENT, CueMode.Focus),
            color = preferences.getInt(COLOR, Color.WHITE),
            size = preferences.getInt(SIZE, 100).coerceIn(50, 300),
            opacity = preferences.getInt(OPACITY, 85).coerceIn(15, 100),
            density = preferences.getInt(DENSITY, 100).coerceIn(40, 180),
            sensitivity = preferences.getInt(SENSITIVITY, 100).coerceIn(30, 200),
            forwardMotion = preferences.getBoolean(FORWARD_MOTION, false),
            frameRate = preferences.getInt(FRAME_RATE, 0).coerceIn(0, 1000),
        )

    val language: String get() = preferences.getString(LANGUAGE, "").orEmpty()
    val fullScreen: Boolean get() = preferences.getBoolean(FULL_SCREEN, false)
    /** Trip detection keeps running without the app and comes back after a reboot. */
    val backgroundTrip: Boolean get() = preferences.getBoolean(BACKGROUND_TRIP, false)
    val interfaceTheme: InterfaceTheme get() = readEnum(INTERFACE_THEME, InterfaceTheme.System)
    val themeColor: Int get() = preferences.getInt(THEME_COLOR, DEFAULT_THEME_COLOR) or 0xFF000000.toInt()

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T {
        val name = preferences.getString(key, null) ?: return fallback
        return enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }

    fun resetAppearance() {
        val editor = preferences.edit()
        for (key in arrayOf(STYLE, PLACEMENT, COLOR, SIZE, OPACITY, DENSITY, SENSITIVITY)) {
            editor.remove(key)
        }
        editor.apply()
    }

    companion object {
        const val STYLE = "style"
        const val PLACEMENT = "placement"
        const val COLOR = "color"
        const val SIZE = "size"
        const val OPACITY = "opacity"
        const val DENSITY = "density"
        const val SENSITIVITY = "sensitivity"
        const val FORWARD_MOTION = "forward_motion"
        const val LANGUAGE = "language"
        const val FULL_SCREEN = "full_screen"
        const val INTERFACE_THEME = "interface_theme"
        const val IMAGE_REVISION = "image_revision"
        const val FRAME_RATE = "frame_rate"
        const val THEME_COLOR = "theme_color"
        const val BACKGROUND_TRIP = "background_trip"

        const val DEFAULT_MARKER_COLOR = Color.WHITE
        const val DEFAULT_THEME_COLOR = 0xFF8CF0CA.toInt()
    }
}

@Suppress("DEPRECATION")
fun Context.localized(): Context {
    val language = AnchorSettings(this).language
    if (language.isEmpty()) return this
    val locale = Locale(language)
    val config = Configuration(resources.configuration)
    if (Build.VERSION.SDK_INT >= 17) {
        config.setLocale(locale)
        return createConfigurationContext(config)
    }
    config.locale = locale
    val overrides = Resources(assets, resources.displayMetrics, config)
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = overrides
    }
}
