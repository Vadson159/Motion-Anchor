// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor

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
) {
    val isDark: Boolean get() = platformTheme != R.style.Theme_MotionAnchor

    companion object {
        fun forTheme(theme: InterfaceTheme): InterfacePalette = when (theme) {
            InterfaceTheme.System, InterfaceTheme.Light -> InterfacePalette(
                platformTheme = R.style.Theme_MotionAnchor,
                page = 0xFFF5F6F2.toInt(),
                panel = 0xFFFFFFFF.toInt(),
                header = 0xFF152E32.toInt(),
                systemBars = 0xFF152E32.toInt(),
                text = 0xFF152E32.toInt(),
                secondary = 0xFF4D6768.toInt(),
                border = 0xFFD7E2DC.toInt(),
                button = 0xFFEDF4F0.toInt(),
                pressed = 0xFFC1E8D8.toInt(),
                disabled = 0xFFDBE4DE.toInt(),
            )
            InterfaceTheme.Dark -> InterfacePalette(
                platformTheme = R.style.Theme_MotionAnchor_Dark,
                page = 0xFF0E1B1B.toInt(),
                panel = 0xFF172726.toInt(),
                header = 0xFF213C3C.toInt(),
                systemBars = 0xFF0E1B1B.toInt(),
                text = 0xFFECF5F2.toInt(),
                secondary = 0xFFB5CBC5.toInt(),
                border = 0xFF405754.toInt(),
                button = 0xFF2B4541.toInt(),
                pressed = 0xFF42675E.toInt(),
                disabled = 0xFF293936.toInt(),
            )
            InterfaceTheme.Black -> InterfacePalette(
                platformTheme = R.style.Theme_MotionAnchor_Black,
                page = 0xFF000000.toInt(),
                panel = 0xFF101817.toInt(),
                header = 0xFF132927.toInt(),
                systemBars = 0xFF000000.toInt(),
                text = 0xFFF3FAF6.toInt(),
                secondary = 0xFFBED0CA.toInt(),
                border = 0xFF344740.toInt(),
                button = 0xFF213431.toInt(),
                pressed = 0xFF3B5C53.toInt(),
                disabled = 0xFF222C29.toInt(),
            )
        }
    }
}
