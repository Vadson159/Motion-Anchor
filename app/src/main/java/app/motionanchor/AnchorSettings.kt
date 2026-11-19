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
        )

    val language: String get() = preferences.getString(LANGUAGE, "").orEmpty()
    val fullScreen: Boolean get() = preferences.getBoolean(FULL_SCREEN, false)
    val interfaceTheme: InterfaceTheme get() = readEnum(INTERFACE_THEME, InterfaceTheme.System)

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == preferences.getString(key, null) } ?: fallback

    fun resetAppearance() {
        val editor = preferences.edit()
        for (key in listOf(STYLE, PLACEMENT, COLOR, SIZE, OPACITY, DENSITY, SENSITIVITY)) {
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
        const val LANGUAGE = "language"
        const val FULL_SCREEN = "full_screen"
        const val INTERFACE_THEME = "interface_theme"
        const val IMAGE_REVISION = "image_revision"
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
