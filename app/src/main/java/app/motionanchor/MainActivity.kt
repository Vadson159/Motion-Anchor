// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.motionanchor.motion.AnchorAccessibilityService
import app.motionanchor.motion.CueMode
import app.motionanchor.motion.CueOverlayView
import app.motionanchor.motion.MotionAnchorService
import app.motionanchor.motion.SessionState
import java.util.Locale
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var settings: AnchorSettings
    private lateinit var palette: InterfacePalette
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var notifications: Button
    private lateinit var overlayPermission: Button
    private lateinit var trip: Button
    private lateinit var previewCard: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var preview: CueOverlayView
    private lateinit var style: Button
    private lateinit var placement: Button
    private lateinit var color: Button
    private lateinit var swatch: View
    private lateinit var chooseImage: Button
    private lateinit var removeImage: Button
    private lateinit var density: Slider
    private var sliders = mutableListOf<Slider>()
    private var fullScreen: Switch? = null
    private var accessibilitySettings: Button? = null
    private var importedRevision: Int? = null
    private var importedMarker: android.graphics.Bitmap? = null
    private var importing = false

    private val sessionListener: () -> Unit = ::refreshSession
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshAppearance()
        refreshSession()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.localized())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AnchorSettings(this)
        val followsDarkSystem = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val theme = when (settings.interfaceTheme) {
            InterfaceTheme.System -> if (followsDarkSystem) InterfaceTheme.Dark else InterfaceTheme.Light
            else -> settings.interfaceTheme
        }
        palette = InterfacePalette.forTheme(theme)
        setTheme(palette.platformTheme)
        super.onCreate(savedInstanceState)
        val root = stack().apply { setBackgroundColor(palette.systemBars) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.page)
            clipToPadding = false
        }
        body = stack().apply {
            setPadding(dp(18), dp(18), dp(18), dp(25))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        addSession()
        addAppearance()
        addTripAndSystem()
        addInterfaceSettings()
        refreshAppearance()
        refreshSession()
    }

    override fun onStart() {
        super.onStart()
        MotionAnchorService.listeners.add(sessionListener)
        settings.preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        refreshAppearance()
    }

    override fun onResume() {
        super.onResume()
        refreshSession()
    }

    override fun onStop() {
        MotionAnchorService.listeners.remove(sessionListener)
        settings.preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        super.onStop()
    }

    private fun addSession() {
        val card = panel(palette.header, border = false)
        card.addView(label(getString(R.string.app_name), 32f, Color.WHITE, bold = true))
        card.addView(label(getString(R.string.intro), 15f, LIGHT_TEXT), gap(5))
        status = label("", 15f, MINT, bold = true).also { card.addView(it, gap(16)) }
        primary = action("", prominent = true) {
            if (MotionAnchorService.state != SessionState.Off) {
                MotionAnchorService.stop(this)
            } else {
                startSession(automatic = false)
            }
        }.also { card.addView(it, gap(9)) }
        overlayPermission = action(getString(R.string.action_overlay)) {
            if (Build.VERSION.SDK_INT >= 23) {
                openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }.also { card.addView(it, gap(7)) }
        notifications = action(getString(R.string.action_notifications)) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }.also { card.addView(it, gap(7)) }
        body.addView(card, gap(0))
    }

    private fun addAppearance() {
        section(R.string.section_appearance)
        val card = panel()
        previewCard = stack().apply {
            setPadding(dp(14), dp(12), dp(14), dp(5))
        }
        previewTitle = label(getString(R.string.section_preview), 14f, LIGHT_TEXT, bold = true)
        previewCard.addView(previewTitle)
        preview = CueOverlayView(this, preview = true)
        previewCard.addView(preview, gap(0, dp(120)))
        card.addView(previewCard)
        style = option(card, R.string.style_label) {
            val styles = CueStyle.entries
            choose(R.string.style_label, styles.map { getString(it.title) }, settings.appearance.style.ordinal) { index ->
                val selected = styles[index]
                if (selected == CueStyle.Image && !MarkerImage.exists(this)) {
                    pickImage()
                } else {
                    setEnum(AnchorSettings.STYLE, selected)
                }
            }
        }
        placement = option(card, R.string.placement_label) {
            choose(
                R.string.placement_label,
                listOf(getString(R.string.placement_focus), getString(R.string.placement_uniform)),
                if (settings.appearance.placement == CueMode.Focus) 0 else 1,
            ) { setEnum(AnchorSettings.PLACEMENT, if (it == 0) CueMode.Focus else CueMode.Uniform) }
        }
        val colorRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        swatch = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        colorRow.addView(swatch, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(9) })
        color = action("", clicked = ::showPalette)
        colorRow.addView(color, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(colorRow, gap(8))
        addSlider(card, R.string.size_label, AnchorSettings.SIZE, 50, 300) { it.size }
        addSlider(card, R.string.opacity_label, AnchorSettings.OPACITY, 15, 100) { it.opacity }
        density = addSlider(card, R.string.density_label, AnchorSettings.DENSITY, 40, 180) { it.density }
        addSlider(card, R.string.sensitivity_label, AnchorSettings.SENSITIVITY, 30, 200) { it.sensitivity }
        card.addView(label(getString(R.string.image_hint), 14f, palette.secondary), gap(12))
        chooseImage = action(getString(R.string.image_choose), clicked = ::pickImage)
            .also { card.addView(it, gap(7)) }
        removeImage = action(getString(R.string.image_remove)) {
            importing = true
            refreshAppearance()
            MarkerImage.remove(this) { success -> finishImageEdit(success, R.string.image_remove_error) }
        }.also { card.addView(it, gap(6)) }
        card.addView(action(getString(R.string.appearance_reset)) { settings.resetAppearance() }, gap(10))
        body.addView(card, gap(8))
    }

    private fun addTripAndSystem() {
        section(R.string.section_trip)
        val card = panel()
        card.addView(label(getString(R.string.trip_hint), 15f, palette.secondary))
        trip = action(getString(R.string.action_arm)) { enableTravel() }
            .also { card.addView(it, gap(9)) }
        if (Build.VERSION.SDK_INT >= 22) {
            fullScreen = Switch(this).apply {
                text = getString(R.string.full_screen_label)
                setTextColor(palette.text)
                textSize = 16f
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    if (settings.fullScreen != checked) {
                        settings.preferences.edit().putBoolean(AnchorSettings.FULL_SCREEN, checked).apply()
                    }
                }
            }.also { card.addView(it, gap(12)) }
            card.addView(label(getString(R.string.accessibility_description), 14f, palette.secondary), gap(3))
            accessibilitySettings = action(getString(R.string.accessibility_open)) {
                openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.also { card.addView(it, gap(7)) }
        }
        if (Build.VERSION.SDK_INT >= 24) {
            card.addView(label(getString(R.string.tile_hint), 14f, palette.secondary), gap(14))
        }
        body.addView(card, gap(8))
    }

    private fun addInterfaceSettings() {
        section(R.string.section_interface)
        val card = panel()
        val themes = InterfaceTheme.entries
        val selectedTheme = settings.interfaceTheme
        option(card, R.string.theme_label) {
            choose(R.string.theme_label, themes.map { getString(it.title) }, selectedTheme.ordinal) { index ->
                val next = themes[index]
                if (next != selectedTheme) {
                    setEnum(AnchorSettings.INTERFACE_THEME, next)
                    recreate()
                }
            }
        }.setText(selectedTheme.title)
        val names = listOf(getString(R.string.language_system), "English", "Русский", "Polski")
        val tags = listOf("", "en", "ru", "pl")
        val selected = tags.indexOf(settings.language).coerceAtLeast(0)
        option(card, R.string.section_language) {
            choose(R.string.section_language, names, selected) { index ->
                if (index != selected) {
                    settings.preferences.edit().putString(AnchorSettings.LANGUAGE, tags[index]).apply()
                    recreate()
                }
            }
        }.text = names[selected]
        body.addView(card, gap(8))
    }

    private fun refreshSession() {
        val state = MotionAnchorService.state
        status.setText(when (state) {
            SessionState.Off -> R.string.status_off
            SessionState.Waiting -> R.string.status_waiting
            SessionState.Showing -> R.string.status_active
        })
        val canOverlay = MotionAnchorService.canShowOverlay(this)
        primary.setText(if (state == SessionState.Off) R.string.action_start else R.string.action_stop)
        primary.isEnabled = canOverlay || state != SessionState.Off
        overlayPermission.visibility = if (canOverlay) View.GONE else View.VISIBLE
        trip.isEnabled = canOverlay && state == SessionState.Off
        notifications.visibility = if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) View.VISIBLE else View.GONE
        fullScreen?.isChecked = settings.fullScreen
        accessibilitySettings?.visibility = if (settings.fullScreen && AnchorAccessibilityService.current == null) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun refreshAppearance() {
        val appearance = settings.appearance
        val hasImage = MarkerImage.exists(this)
        val revision = settings.preferences.getInt(AnchorSettings.IMAGE_REVISION, 0)
        if (importedRevision != revision) {
            importedRevision = revision
            importedMarker = if (hasImage) MarkerImage.load(this) else null
        }
        preview.setAppearance(appearance, importedMarker)
        val rgb = appearance.color
        val brightness = (299 * Color.red(rgb) + 587 * Color.green(rgb) + 114 * Color.blue(rgb)) / 1000
        val contrastThreshold = if (palette.isDark) 75 else 115
        val lightPreview = appearance.style != CueStyle.Image && brightness < contrastThreshold
        val previewBackground = when {
            lightPreview && palette.isDark -> PREVIEW_MUTED
            lightPreview -> PREVIEW_LIGHT
            palette.isDark -> palette.page
            else -> palette.header
        }
        previewCard.background = rounded(previewBackground, 13)
        previewTitle.setTextColor(if (lightPreview) PRIMARY_TEXT else LIGHT_TEXT)
        style.setText(appearance.style.title)
        placement.setText(
            if (appearance.placement == CueMode.Focus) R.string.placement_focus else R.string.placement_uniform,
        )
        color.text = getString(R.string.color_value, hex(appearance.color))
        color.isEnabled = appearance.style != CueStyle.Image
        swatch.background = rounded(appearance.color, 15, palette.border)
        density.container.visibility = if (appearance.style == CueStyle.Horizon) View.GONE else View.VISIBLE
        sliders.forEach { it.render(appearance) }
        chooseImage.setText(when {
            importing -> R.string.image_importing
            hasImage -> R.string.image_replace
            else -> R.string.image_choose
        })
        chooseImage.isEnabled = !importing
        removeImage.visibility = if (hasImage) View.VISIBLE else View.GONE
        removeImage.isEnabled = !importing
    }

    private fun startSession(automatic: Boolean) {
        if (!MotionAnchorService.start(this, automatic)) displayMessage(R.string.session_failed)
    }

    private fun enableTravel() {
        if (!MotionAnchorService.hasPreciseLocation(this)) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_LOCATION,
                )
            } else {
                displayMessage(R.string.trip_location_required)
            }
            return
        }
        if (!MotionAnchorService.isGpsEnabled(this)) {
            displayMessage(R.string.trip_gps_required)
            openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        startSession(automatic = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (MotionAnchorService.hasPreciseLocation(this)) enableTravel() else displayMessage(R.string.trip_location_required)
        }
        refreshSession()
    }

    private fun pickImage() {
        if (importing) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(intent, REQUEST_IMAGE)
        } catch (_: ActivityNotFoundException) {
            displayMessage(R.string.image_picker_error)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importing = true
        refreshAppearance()
        MarkerImage.import(this, uri) { success -> finishImageEdit(success, R.string.image_error) }
    }

    private fun finishImageEdit(success: Boolean, errorMessage: Int) {
        runOnUiThread {
            importing = false
            if (!isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) {
                if (!success) displayMessage(errorMessage)
                refreshAppearance()
            }
        }
    }

    private fun showPalette() {
        val palette = listOf(
            R.string.color_white to Color.WHITE,
            R.string.color_black to Color.BLACK,
            R.string.color_aqua to 0xFF63E6BE.toInt(),
            R.string.color_coral to 0xFFFF786B.toInt(),
            R.string.color_gold to 0xFFFFCC61.toInt(),
            R.string.color_blue to 0xFF78BCFF.toInt(),
        )
        val labels = palette.map { (title, value) -> "${getString(title)}  ·  ${hex(value)}" } +
            getString(R.string.color_custom)
        choose(R.string.color_label, labels, palette.indexOfFirst { it.second == settings.appearance.color }) { index ->
            if (index == palette.size) showHexInput() else saveColor(palette[index].second)
        }
    }

    private fun showHexInput() {
        val field = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(7))
            setText(hex(settings.appearance.color))
            selectAll()
        }
        val content = stack().apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.color_custom).setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = field.text.toString().trim().removePrefix("#")
                val rgb = input.toIntOrNull(16)
                if (input.matches(HEX_COLOR) && rgb != null) {
                    saveColor(0xFF000000.toInt() or rgb)
                    dialog.dismiss()
                } else {
                    field.error = getString(R.string.color_invalid)
                }
            }
        }
        dialog.show()
    }

    private fun saveColor(color: Int) {
        settings.preferences.edit().putInt(AnchorSettings.COLOR, color).apply()
    }

    private fun setEnum(key: String, value: Enum<*>) {
        settings.preferences.edit().putString(key, value.name).apply()
    }

    private fun hex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun displayMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            displayMessage(R.string.permission_settings_error)
        }
    }

    private fun choose(title: Int, options: List<String>, index: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), index) { dialog, selected ->
                dialog.dismiss()
                onPick(selected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun option(parent: LinearLayout, title: Int, onClick: () -> Unit): Button {
        parent.addView(label(getString(title), 14f, palette.secondary, bold = true), gap(12))
        return action("", clicked = onClick).also { parent.addView(it, gap(4)) }
    }

    private fun addSlider(
        parent: LinearLayout, title: Int, key: String, minimum: Int, maximum: Int, value: (CueAppearance) -> Int,
    ): Slider = Slider(title, key, minimum, maximum, value).also {
        sliders.add(it)
        parent.addView(it.container, gap(9))
    }

    private inner class Slider(
        private val title: Int, key: String, private val minimum: Int, maximum: Int,
        private val value: (CueAppearance) -> Int,
    ) {
        val container = stack()
        private val caption = label("", 15f, palette.text)
        private val bar = SeekBar(this@MainActivity).apply {
            max = maximum - minimum
            contentDescription = getString(title)
            minimumHeight = dp(42)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) settings.preferences.edit().putInt(key, progress + minimum).apply()
                }
            })
        }
        init {
            container.addView(caption)
            container.addView(bar, gap(0, dp(45)))
        }
        fun render(appearance: CueAppearance) {
            val current = value(appearance)
            caption.text = getString(R.string.slider_value, getString(title), current)
            bar.progress = current - minimum
        }
    }

    private fun section(title: Int) {
        body.addView(label(getString(title), 21f, palette.text, bold = true), gap(22))
    }

    private fun stack() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun panel(fill: Int = palette.panel, border: Boolean = true) = stack().apply {
        background = rounded(fill, 18, if (border) palette.border else null)
        setPadding(dp(15), dp(15), dp(15), dp(15))
    }

    private fun gap(top: Int, height: Int = -2) = LinearLayout.LayoutParams(-1, height).apply {
        topMargin = dp(top)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun action(value: String, prominent: Boolean = false, clicked: () -> Unit) = Button(this).apply {
        text = value
        textSize = 15f
        setAllCaps(false)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(13), dp(8), dp(13), dp(8))
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        setTextColor(
            ColorStateList(
                arrayOf(disabled, intArrayOf()),
                intArrayOf(palette.secondary, if (prominent) PRIMARY_TEXT else palette.text),
            ),
        )
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        background = StateListDrawable().apply {
            addState(disabled, rounded(palette.disabled, 10))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(if (prominent) PRESSED_MINT else palette.pressed, 10))
            addState(intArrayOf(android.R.attr.state_focused), rounded(if (prominent) FOCUSED_MINT else palette.pressed, 10))
            addState(intArrayOf(), rounded(if (prominent) MINT else palette.button, 10))
        }
        setOnClickListener { clicked() }
    }

    private fun rounded(fill: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private val PRIMARY_TEXT = 0xFF152E32.toInt()
        private val LIGHT_TEXT = 0xFFD8E8E5.toInt()
        private val MINT = 0xFF8CF0CA.toInt()
        private val PRESSED_MINT = 0xFF75D9B1.toInt()
        private val FOCUSED_MINT = 0xFFA6F7DA.toInt()
        private val PREVIEW_LIGHT = 0xFFEDF4F0.toInt()
        private val PREVIEW_MUTED = 0xFF78988F.toInt()
        private val HEX_COLOR = Regex("[0-9a-fA-F]{6}")
        private const val REQUEST_IMAGE = 1
        private const val REQUEST_LOCATION = 2
        private const val REQUEST_NOTIFICATIONS = 3
    }
}
