// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.motionanchor.motion.CueMode
import app.motionanchor.motion.CueOverlayView
import app.motionanchor.motion.FrameRates
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
    private lateinit var previewBackground: GradientDrawable
    private lateinit var previewTitle: TextView
    private lateinit var preview: CueOverlayView
    private lateinit var style: Button
    private lateinit var placement: Button
    private lateinit var color: Button
    private lateinit var swatch: GradientDrawable
    private lateinit var frameRate: Button
    private lateinit var forwardMotion: CheckBox
    private lateinit var chooseImage: Button
    private lateinit var removeImage: Button
    private lateinit var density: Slider
    private var sliders = mutableListOf<Slider>()
    private var fullScreen: Switch? = null
    private lateinit var background: Switch
    private lateinit var battery: Button
    // Background mode turns on only once its permissions are settled; until then the switch
    // shows the request in progress.
    private var pendingBackground = false
    private var backgroundSettingsOpened = false
    private var backgroundRequestedAt = 0L
    private var accessibilitySettings: Button? = null
    private var importing = false

    private val sessionListener: () -> Unit = { refreshSession() }
    // Sliders write on every step, so only redo the part that depends on the changed key.
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AnchorSettings.FULL_SCREEN || key == AnchorSettings.BACKGROUND_TRIP) refreshSession() else refreshAppearance()
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
        palette = InterfacePalette.forTheme(theme, settings.themeColor)
        setTheme(palette.platformTheme)
        super.onCreate(savedInstanceState)
        // The theme resources hold the default colors; the chosen theme color replaces them here.
        window.setBackgroundDrawable(ColorDrawable(palette.systemBars))
        if (Build.VERSION.SDK_INT in 21..34) {
            window.statusBarColor = palette.systemBars
            window.navigationBarColor = palette.systemBars
        }
        // The theme's window background already has the system bar color, so the root stays
        // transparent rather than painting the whole screen a second time.
        val root = stack()
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
        addSupport()
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
        if (backgroundSettingsOpened) {
            // Back from the location page: continue with whatever the user chose there.
            backgroundSettingsOpened = false
            finishBackground()
        } else if (settings.backgroundTrip && !pendingBackground && MotionAnchorService.state == SessionState.Off &&
            MotionAnchorService.canShowOverlay(this) && MotionAnchorService.hasPreciseLocation(this)
        ) {
            // Background mode is on but its session ended (a reboot without "Allow all the
            // time", or Android stopped it); opening the app brings it back.
            MotionAnchorService.start(this, automatic = true)
        }
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
        card.addView(label(getString(R.string.intro), 15f, palette.headerText), gap(5))
        status = label("", 15f, palette.headerAccent, bold = true).also { card.addView(it, gap(16)) }
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
        previewBackground = rounded(palette.header, 13)
        previewCard = stack().apply {
            background = previewBackground
            setPadding(dp(14), dp(12), dp(14), dp(5))
        }
        previewTitle = label(getString(R.string.section_preview), 14f, palette.headerText, bold = true)
        previewCard.addView(previewTitle)
        preview = CueOverlayView(this, preview = true)
        previewCard.addView(preview, gap(0, dp(120)))
        card.addView(previewCard)
        style = option(card, R.string.style_label) {
            val styles = CueStyle.values()
            choose(R.string.style_label, Array(styles.size) { getString(styles[it].title) }, settings.appearance.style.ordinal) { index ->
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
                arrayOf(getString(R.string.placement_focus), getString(R.string.placement_uniform)),
                if (settings.appearance.placement == CueMode.Focus) 0 else 1,
            ) { setEnum(AnchorSettings.PLACEMENT, if (it == 0) CueMode.Focus else CueMode.Uniform) }
        }
        swatch = rounded(Color.WHITE, 15, palette.border)
        color = colorRow(card, swatch) {
            pickColor(R.string.color_label, settings.appearance.color, AnchorSettings.DEFAULT_MARKER_COLOR) { saveColor(it) }
        }
        addSlider(card, R.string.size_label, AnchorSettings.SIZE, 50, 300) { it.size }
        addSlider(card, R.string.opacity_label, AnchorSettings.OPACITY, 15, 100) { it.opacity }
        density = addSlider(card, R.string.density_label, AnchorSettings.DENSITY, 40, 180) { it.density }
        addSlider(card, R.string.sensitivity_label, AnchorSettings.SENSITIVITY, 30, 200) { it.sensitivity }
        forwardMotion = checkbox(R.string.forward_motion_label) { checked ->
            if (settings.appearance.forwardMotion != checked) {
                settings.preferences.edit().putBoolean(AnchorSettings.FORWARD_MOTION, checked).apply()
            }
        }.also { card.addView(it, gap(8)) }
        card.addView(label(getString(R.string.forward_motion_hint), 14f, palette.secondary), gap(2))
        frameRate = option(card, R.string.frame_rate_label) { chooseFrameRate() }
        card.addView(label(getString(R.string.image_hint), 14f, palette.secondary), gap(12))
        chooseImage = action(getString(R.string.image_choose)) { pickImage() }
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
        background = toggle(R.string.background_label) { checked ->
            if (checked != (settings.backgroundTrip || pendingBackground)) {
                if (checked) startBackground() else stopBackground()
            }
        }.also { card.addView(it, gap(12)) }
        card.addView(label(getString(R.string.background_hint), 14f, palette.secondary), gap(3))
        battery = action(getString(R.string.battery_action)) { requestBatteryExemption() }
            .also { card.addView(it, gap(7)) }
        if (BuildConfig.ACCESSIBILITY_OVERLAY && Build.VERSION.SDK_INT >= 22) {
            fullScreen = toggle(R.string.full_screen_label) { checked ->
                if (settings.fullScreen != checked) {
                    settings.preferences.edit().putBoolean(AnchorSettings.FULL_SCREEN, checked).apply()
                }
            }.also { card.addView(it, gap(18)) }
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
        val themes = InterfaceTheme.values()
        val selectedTheme = settings.interfaceTheme
        option(card, R.string.theme_label) {
            choose(R.string.theme_label, Array(themes.size) { getString(themes[it].title) }, selectedTheme.ordinal) { index ->
                val next = themes[index]
                if (next != selectedTheme) {
                    setEnum(AnchorSettings.INTERFACE_THEME, next)
                    recreate()
                }
            }
        }.setText(selectedTheme.title)
        val themeColor = settings.themeColor
        colorRow(card, rounded(themeColor, 15, palette.border)) {
            pickColor(R.string.theme_color_label, themeColor, AnchorSettings.DEFAULT_THEME_COLOR) { picked ->
                if (picked != themeColor) {
                    settings.preferences.edit().putInt(AnchorSettings.THEME_COLOR, picked).apply()
                    recreate()
                }
            }
        }.text = getString(R.string.theme_color_value, hex(themeColor))
        val names = arrayOf(getString(R.string.language_system), "English", "Русский", "Polski")
        val tags = arrayOf("", "en", "ru", "pl")
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

    private fun addSupport() {
        section(R.string.section_support)
        val card = panel()
        card.addView(label(getString(R.string.support_hint), 14f, palette.secondary))
        card.addView(action(getString(R.string.support_action)) { openLink(SUPPORT_URL) }, gap(10))
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
        trip.setText(if (state != SessionState.Off && MotionAnchorService.tripMode) R.string.trip_active else R.string.action_arm)
        notifications.visibility = if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) View.VISIBLE else View.GONE
        background.isChecked = settings.backgroundTrip || pendingBackground
        background.isEnabled = canOverlay || background.isChecked
        battery.visibility = if (settings.backgroundTrip && !MotionAnchorService.isIgnoringBatteryOptimizations(this)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        fullScreen?.isChecked = settings.fullScreen
        accessibilitySettings?.visibility = if (settings.fullScreen && MotionAnchorService.accessibilityHost() == null) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun refreshAppearance() {
        val appearance = settings.appearance
        val hasImage = MarkerImage.exists(this)
        // Decode the image only while it is the selected marker.
        preview.setAppearance(appearance, if (hasImage && appearance.style == CueStyle.Image) MarkerImage.load(this) else null)
        val rgb = appearance.color
        val brightness = (299 * Color.red(rgb) + 587 * Color.green(rgb) + 114 * Color.blue(rgb)) / 1000
        val contrastThreshold = if (palette.isDark) 75 else 115
        val lightPreview = appearance.style != CueStyle.Image && brightness < contrastThreshold
        previewBackground.setColor(when {
            lightPreview && palette.isDark -> palette.previewMuted
            lightPreview -> palette.previewLight
            palette.isDark -> palette.page
            else -> palette.header
        })
        previewTitle.setTextColor(if (lightPreview) palette.onPreviewLight else palette.headerText)
        style.setText(appearance.style.title)
        placement.setText(
            if (appearance.placement == CueMode.Focus) R.string.placement_focus else R.string.placement_uniform,
        )
        color.text = getString(R.string.color_value, hex(appearance.color))
        color.isEnabled = appearance.style != CueStyle.Image
        swatch.setColor(appearance.color)
        density.container.visibility = if (appearance.style == CueStyle.Horizon) View.GONE else View.VISIBLE
        forwardMotion.isChecked = appearance.forwardMotion
        frameRate.text = frameRateName(appearance.frameRate)
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
        if (MotionAnchorService.start(this, automatic = true)) {
            Toast.makeText(this, R.string.trip_enabled, Toast.LENGTH_LONG).show()
        } else {
            displayMessage(R.string.session_failed)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && permissions.isNotEmpty()) {
            when {
                MotionAnchorService.hasPreciseLocation(this) -> if (pendingBackground) continueBackground() else enableTravel()
                // After a second refusal, or once only approximate location was granted, Android
                // answers at once without showing a dialog. Only the app settings can change it.
                Build.VERSION.SDK_INT >= 23 &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                    pendingBackground = false
                    explainLocation()
                }
                else -> {
                    pendingBackground = false
                    displayMessage(R.string.trip_location_required)
                }
            }
        }
        if (requestCode == REQUEST_BACKGROUND_LOCATION && permissions.isNotEmpty()) {
            when {
                MotionAnchorService.hasBackgroundLocation(this) -> finishBackground()
                // An answer this fast means Android refused without asking, which it does after
                // earlier refusals. The option is then only on the app's location page.
                SystemClock.elapsedRealtime() - backgroundRequestedAt < 500L &&
                    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) ->
                    backgroundSettingsOpened = true
                else -> finishBackground()
            }
        }
        refreshSession()
    }

    private fun startBackground() {
        if (!MotionAnchorService.canShowOverlay(this)) {
            displayMessage(R.string.session_failed)
            refreshSession()
            return
        }
        pendingBackground = true
        continueBackground()
    }

    private fun continueBackground() {
        if (!MotionAnchorService.hasPreciseLocation(this)) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_LOCATION,
                )
            } else {
                pendingBackground = false
                displayMessage(R.string.trip_location_required)
                refreshSession()
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && !MotionAnchorService.hasBackgroundLocation(this)) {
            askBackgroundLocation()
            return
        }
        finishBackground()
    }

    /** Android shows "Allow all the time" only on a separate page, so say why first. */
    @TargetApi(29)
    private fun askBackgroundLocation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.background_location_title)
            .setMessage(R.string.background_location_message)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                backgroundRequestedAt = SystemClock.elapsedRealtime()
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND_LOCATION)
            }
            .setNegativeButton(R.string.action_skip) { _, _ -> finishBackground() }
            .setOnCancelListener {
                pendingBackground = false
                refreshSession()
            }
            .show()
    }

    private fun finishBackground() {
        pendingBackground = false
        settings.preferences.edit().putBoolean(AnchorSettings.BACKGROUND_TRIP, true).apply()
        // A manual overlay becomes a trip session; a running trip session only turns sticky.
        if (MotionAnchorService.state != SessionState.Off && !MotionAnchorService.tripMode) {
            MotionAnchorService.stop(this, keepBackground = true)
        }
        val started = MotionAnchorService.start(this, automatic = true)
        val message = when {
            !started -> R.string.session_failed
            !MotionAnchorService.hasBackgroundLocation(this) -> R.string.background_without_location
            !MotionAnchorService.isGpsEnabled(this) -> R.string.trip_gps_required
            else -> R.string.background_enabled
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refreshSession()
        if (started && !MotionAnchorService.isIgnoringBatteryOptimizations(this)) requestBatteryExemption()
    }

    private fun stopBackground() {
        settings.preferences.edit().putBoolean(AnchorSettings.BACKGROUND_TRIP, false).apply()
        if (MotionAnchorService.state != SessionState.Off && MotionAnchorService.tripMode) MotionAnchorService.stop(this)
        refreshSession()
    }

    /** Some vendors stop long-running services of apps under battery optimization. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun explainLocation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.trip_permission_title)
            .setMessage(R.string.trip_permission_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_app_settings) { _, _ ->
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .show()
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

    private fun chooseFrameRate() {
        val native = FrameRates.supported(this)
        val rates = intArrayOf(0) + FrameRates.options(native)
        val current = settings.appearance.frameRate
        val index = rates.indexOf(current)
        // Rates that are display modes are marked: they run without skipped refreshes.
        val names = Array(rates.size) {
            val rate = rates[it]
            if (rate > 0 && rate in native) getString(R.string.frame_rate_native, rate) else frameRateName(rate, native)
        } + if (index < 0) getString(R.string.frame_rate_custom_value, current) else getString(R.string.frame_rate_custom)
        choose(R.string.frame_rate_label, names, if (index >= 0) index else rates.size) { picked ->
            if (picked < rates.size) saveFrameRate(rates[picked]) else enterFrameRate(native.last())
        }
    }

    private fun enterFrameRate(fastest: Int) {
        val limit = FrameRates.CUSTOM_MAX
        val field = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(limit.toString().length))
            hint = getString(R.string.frame_rate_value, fastest)
            contentDescription = getString(R.string.frame_rate_label)
            settings.appearance.frameRate.takeIf { it > 0 }?.let { setText(String.format(Locale.ROOT, "%d", it)) }
            setSelection(text.length)
        }
        val content = stack().apply {
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.frame_rate_custom_title)
            .setMessage(getString(R.string.frame_rate_custom_hint, limit, fastest))
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rate = field.text.toString().trim().toIntOrNull()
                if (rate == null || rate !in 1..limit) {
                    field.error = getString(R.string.frame_rate_invalid, limit)
                } else {
                    dialog.dismiss()
                    saveFrameRate(rate)
                }
            }
        }
        dialog.show()
    }

    private fun saveFrameRate(rate: Int) {
        settings.preferences.edit().putInt(AnchorSettings.FRAME_RATE, rate).apply()
    }

    private fun frameRateName(rate: Int, native: IntArray = FrameRates.supported(this)): String =
        if (rate > 0) getString(R.string.frame_rate_value, rate)
        else getString(R.string.frame_rate_max, native.last())

    /** Palette dialog: drag on the field and the hue strip, or type a hex value. */
    private fun pickColor(title: Int, initial: Int, default: Int, onPick: (Int) -> Unit) {
        val picker = ColorPickerView(this).apply { setColor(initial) }
        val preview = rounded(initial, 15, palette.border)
        val field = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(7))
            hint = "#RRGGBB"
            contentDescription = getString(R.string.color_hex)
            setText(hex(initial))
        }
        var syncing = false
        picker.onColorChanged = { picked ->
            preview.setColor(picked)
            syncing = true
            field.setText(hex(picked))
            field.error = null
            syncing = false
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                if (syncing) return
                val parsed = parseHex(s.toString()) ?: return
                picker.setColor(parsed)
                preview.setColor(parsed)
            }
        })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = preview
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        val content = stack().apply {
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(picker, LinearLayout.LayoutParams(-1, -2))
            addView(row, gap(10))
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.color_default, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                picker.setColor(default)
                picker.onColorChanged?.invoke(default)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parseHex(field.text.toString())
                if (parsed == null) {
                    field.error = getString(R.string.color_invalid)
                } else {
                    dialog.dismiss()
                    onPick(parsed)
                }
            }
        }
        dialog.show()
    }

    private fun parseHex(text: String): Int? {
        val input = text.trim().removePrefix("#")
        if (input.length != 6 || !input.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return 0xFF000000.toInt() or Integer.parseInt(input, 16)
    }

    private fun colorRow(parent: LinearLayout, swatch: GradientDrawable, onClick: () -> Unit): Button {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = swatch
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(9) })
        val button = action("", clicked = onClick)
        row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row, gap(8))
        return button
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

    private fun openSettings(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        displayMessage(R.string.permission_settings_error)
        false
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            displayMessage(R.string.link_open_error)
        }
    }

    private fun checkbox(title: Int, changed: (Boolean) -> Unit) = CheckBox(this).apply {
        text = getString(title)
        setTextColor(palette.text)
        textSize = 16f
        minHeight = dp(48)
        if (Build.VERSION.SDK_INT >= 21) {
            val on = intArrayOf(android.R.attr.state_checked)
            buttonTintList = ColorStateList(arrayOf(on, intArrayOf()), intArrayOf(palette.control, palette.secondary))
        }
        setOnCheckedChangeListener { _, checked -> changed(checked) }
    }

    private fun toggle(title: Int, changed: (Boolean) -> Unit) = Switch(this).apply {
        text = getString(title)
        setTextColor(palette.text)
        textSize = 16f
        minHeight = dp(48)
        if (Build.VERSION.SDK_INT >= 23) {
            val on = intArrayOf(android.R.attr.state_checked)
            thumbTintList = ColorStateList(arrayOf(on, intArrayOf()), intArrayOf(palette.control, palette.button))
            trackTintList = ColorStateList(arrayOf(on, intArrayOf()), intArrayOf(palette.control, palette.secondary))
        }
        setOnCheckedChangeListener { _, checked -> changed(checked) }
    }

    private fun choose(title: Int, options: Array<String>, index: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options, index) { dialog, selected ->
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
            if (Build.VERSION.SDK_INT >= 21) {
                val tint = ColorStateList.valueOf(palette.control)
                progressTintList = tint
                thumbTintList = tint
            }
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
                intArrayOf(palette.secondary, if (prominent) palette.onAccent else palette.text),
            ),
        )
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        background = StateListDrawable().apply {
            addState(disabled, rounded(palette.disabled, 10))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(if (prominent) palette.accentPressed else palette.pressed, 10))
            addState(intArrayOf(android.R.attr.state_focused), rounded(if (prominent) palette.accentFocused else palette.pressed, 10))
            addState(intArrayOf(), rounded(if (prominent) palette.accent else palette.button, 10))
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
        private const val REQUEST_IMAGE = 1
        private const val REQUEST_LOCATION = 2
        private const val REQUEST_NOTIFICATIONS = 3
        private const val REQUEST_BACKGROUND_LOCATION = 4
        private const val SUPPORT_URL = "https://buymeacoffee.com/vadson"
    }
}
