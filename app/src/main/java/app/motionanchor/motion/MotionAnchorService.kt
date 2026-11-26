// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.Manifest
import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import app.motionanchor.AnchorSettings
import app.motionanchor.BuildConfig
import app.motionanchor.CueStyle
import app.motionanchor.MainActivity
import app.motionanchor.MarkerImage
import app.motionanchor.R
import app.motionanchor.localized

enum class SessionState { Off, Waiting, Showing }

@Suppress("DEPRECATION")
class MotionAnchorService : Service() {
    private lateinit var settings: AnchorSettings
    private lateinit var motionEstimator: MotionEstimator
    private lateinit var locationManager: LocationManager
    private val handler = Handler()
    private var overlayView: CueOverlayView? = null
    private var overlayHost: Context? = null
    private var overlayManager: WindowManager? = null
    private var active = false
    private var drawing = false
    private var detector: TravelDetector? = null
    private var wantsOverlay = false
    private var screenOn = true
    private lateinit var configuration: Configuration

    // Nobody sees the overlay while the screen is off, so its window, sensors, and frame loop
    // are released until the screen comes back. Trip detection keeps running meanwhile.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            syncOverlay()
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AnchorSettings.FULL_SCREEN) syncOverlay()
        if (key == AnchorSettings.FRAME_RATE) updateRefreshRate()
        if (key == AnchorSettings.FORWARD_MOTION) syncSpeedTracking()
        updateAppearance()
        if (key == AnchorSettings.LANGUAGE && active) refreshNotification()
    }

    private var lastFix: Location? = null
    private var lastGpsSpeedAt = 0L
    // A manual session whose foreground service may use location follows GPS speed while the
    // overlay is drawn, so the markers flow with the vehicle instead of fading after a while.
    private var manualSpeed = false
    private var followingSpeed = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            val gps = location.provider == LocationManager.GPS_PROVIDER
            // Doppler speed from GPS is the reliable source; coarser fixes only fill its gaps.
            if (!gps && now - lastGpsSpeedAt < GPS_PRIORITY_MS) return
            var speed = measuredSpeed(location)
            if (speed != null && gps) lastGpsSpeedAt = now
            if (speed == null) speed = derivedSpeed(location)
            lastFix = Location(location)
            motionEstimator.onLocationSpeed(speed)
            val travel = detector ?: return
            showWhenMoving(travel.update(now, speed))
        }
        override fun onProviderDisabled(provider: String) {
            if (provider != LocationManager.GPS_PROVIDER || detector == null) return
            detector = TravelDetector()
            showWhenMoving(false)
        }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val expireTravel = object : Runnable {
        override fun run() {
            val travel = detector ?: return
            showWhenMoving(travel.isMoving(SystemClock.elapsedRealtime()))
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AnchorSettings(this)
        motionEstimator = MotionEstimator(this) { overlayView?.setMotion(it) }
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        configuration = Configuration(resources.configuration)
        settings.preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        if (BuildConfig.ACCESSIBILITY_OVERLAY) AnchorAccessibilityService.connectionListener = { syncOverlay() }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        screenOn = if (Build.VERSION.SDK_INT >= 20) power.isInteractive else power.isScreenOn
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canShowOverlay(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val background = settings.backgroundTrip
        if (active) return if (detector != null && !background) START_NOT_STICKY else START_STICKY

        // A sticky restart after Android killed the process brings no intent. In background mode
        // that restart resumes trip detection; otherwise it was a manual session.
        val automatic = intent?.getBooleanExtra(EXTRA_AUTOMATIC, false) ?: background
        // In background mode GPS may be switched off for a while; the updates resume with it.
        if (automatic && (!hasPreciseLocation(this) || (!background && !isGpsEnabled(this)))) {
            report(if (hasPreciseLocation(this)) R.string.trip_gps_required else R.string.trip_location_required)
            stopSelf()
            return START_NOT_STICKY
        }
        tripMode = automatic
        if (automatic) notificationManager().cancel(RESUME_NOTIFICATION_ID)
        setState(if (automatic) SessionState.Waiting else SessionState.Showing)
        try {
            manualSpeed = startForegroundForSession(automatic)
            active = true
            if (automatic) {
                detector = TravelDetector()
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener)
                requestBackupLocation()
                handler.post(expireTravel)
            } else {
                wantsOverlay = true
                syncOverlay()
            }
        } catch (_: SecurityException) {
            report(R.string.session_failed)
            stopSelf()
        } catch (_: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException on Android 12 and later.
            report(R.string.session_failed)
            stopSelf()
        }
        return if (automatic && !background) START_NOT_STICKY else START_STICKY
    }

    /** A start that fails here would otherwise look like a button that does nothing. */
    private fun report(message: Int) {
        Toast.makeText(localized(), message, Toast.LENGTH_LONG).show()
    }

    /**
     * Inside buses, trains, and pockets GPS may not get a fix at all. Wi-Fi and cell positions
     * are coarse but still show a trip by how far they move.
     */
    private fun requestBackupLocation() {
        val provider = when {
            hasProvider(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            Build.VERSION.SDK_INT >= 31 && hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            else -> return
        }
        try {
            locationManager.requestLocationUpdates(provider, 3_000L, 0f, locationListener)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun hasProvider(name: String): Boolean = try {
        locationManager.getProvider(name) != null && locationManager.isProviderEnabled(name)
    } catch (_: RuntimeException) {
        false
    }

    /** The speed the receiver measured, when it is trustworthy. */
    private fun measuredSpeed(location: Location): Float? {
        if (!location.hasSpeed()) return null
        if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
            return location.speed.takeIf { location.speedAccuracyMetersPerSecond <= 3f }
        }
        return location.speed.takeIf { !location.hasAccuracy() || location.accuracy <= 100f }
    }

    /**
     * Speed from the distance to the previous fix. Only counts when the fixes are further apart
     * than both their error radii together, so a position jumping around a parked phone does
     * not look like a trip. Returns null when the fixes cannot tell motion from noise.
     */
    private fun derivedSpeed(location: Location): Float? {
        val previous = lastFix ?: return null
        val seconds = if (Build.VERSION.SDK_INT >= 17) {
            (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1e9f
        } else {
            (location.time - previous.time) / 1e3f
        }
        if (seconds < 2f || seconds > 60f) return null
        val errors = (if (location.hasAccuracy()) location.accuracy else 100f) +
            (if (previous.hasAccuracy()) previous.accuracy else 100f)
        if (errors > 300f) return null
        val distance = previous.distanceTo(location)
        return if (distance > errors) distance / seconds else null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val changes = configuration.diff(newConfig)
        configuration.setTo(newConfig)
        // Only the language shows in the notification; skip rotations and other changes.
        if (active && (changes and ActivityInfo.CONFIG_LOCALE) != 0) refreshNotification()
    }

    private fun showWhenMoving(moving: Boolean) {
        if (moving == wantsOverlay) return
        wantsOverlay = moving
        setState(if (moving) SessionState.Showing else SessionState.Waiting)
        syncOverlay()
        notificationManager().notify(NOTIFICATION_ID, notification())
    }

    private fun syncOverlay() {
        if (!active) return
        val show = wantsOverlay && screenOn
        val accessibility = accessibilityHost()
            .takeIf { settings.fullScreen && Build.VERSION.SDK_INT >= 22 }
        val host: Context = accessibility ?: this
        if (overlayHost !== host || !show) detachOverlay()
        if (!show || overlayView != null) return

        val attached = if (Build.VERSION.SDK_INT >= 22 && accessibility != null) {
            attachOverlay(accessibility, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else {
            false
        }
        if (!attached) {
            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            if (!canDrawOverApps(this) || !attachOverlay(this, type)) {
                stopSelf()
                return
            }
        }
        if (!drawing) {
            motionEstimator.start()
            drawing = true
            syncSpeedTracking()
        }
    }

    /**
     * Starts or stops manual GPS speed updates to match the forward/backward motion setting.
     * Trip detection already receives location updates for the whole session.
     */
    private fun syncSpeedTracking() {
        if (detector != null) return
        if (!active || !drawing || !settings.appearance.forwardMotion) {
            if (followingSpeed) {
                stopLocationUpdates()
                followingSpeed = false
                lastFix = null
                lastGpsSpeedAt = 0L
            }
            return
        }
        if (!manualSpeed && hasPreciseLocation(this)) {
            manualSpeed = try {
                startForegroundForSession(automatic = false)
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }
        if (manualSpeed) followSpeed()
    }

    /**
     * GPS updates for a manual session. Trip detection already receives them for the whole
     * session; here they only run while the overlay is drawn, so a dark screen costs no GPS.
     */
    @SuppressLint("MissingPermission")
    private fun followSpeed() {
        if (followingSpeed || !hasPreciseLocation(this)) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener)
            followingSpeed = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun attachOverlay(host: Context, type: Int): Boolean {
        val manager = host.getSystemService(WINDOW_SERVICE) as WindowManager
        val view = CueOverlayView(host).apply {
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Without these the window stops below the status bar and the camera cutout, so the
            // band with the clock and battery had no markers.
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
            if (Build.VERSION.SDK_INT >= 21) {
                preferredRefreshRate = FrameRates.target(this@MotionAnchorService, settings.appearance.frameRate).toFloat()
            }
            if (type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && Build.VERSION.SDK_INT >= 31) {
                val input = getSystemService(Context.INPUT_SERVICE) as InputManager
                alpha = (input.maximumObscuringOpacityForTouch - 0.01f).coerceIn(0.01f, 1f)
            }
        }
        try {
            manager.addView(view, params)
        } catch (_: WindowManager.BadTokenException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        overlayView = view
        overlayHost = host
        overlayManager = manager
        updateAppearance()
        return true
    }

    /** Asks the display for the frame rate chosen in the settings. */
    private fun updateRefreshRate() {
        if (Build.VERSION.SDK_INT < 21) return
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.preferredRefreshRate = FrameRates.target(this, settings.appearance.frameRate).toFloat()
        try {
            overlayManager?.updateViewLayout(view, params)
        } catch (_: RuntimeException) {
        }
    }

    private fun updateAppearance() {
        val view = overlayView ?: return
        val appearance = settings.appearance
        view.setAppearance(appearance, if (appearance.style == CueStyle.Image) MarkerImage.load(this) else null)
    }

    private fun detachOverlay() {
        overlayView?.let { view ->
            try {
                overlayManager?.removeViewImmediate(view)
            } catch (_: RuntimeException) {
            }
        }
        overlayView = null
        overlayHost = null
        overlayManager = null
        if (drawing) {
            motionEstimator.stop()
            drawing = false
        }
        if (followingSpeed) {
            stopLocationUpdates()
            followingSpeed = false
            lastFix = null
            lastGpsSpeedAt = 0L
        }
    }

    override fun onDestroy() {
        active = false
        tripMode = false
        setState(SessionState.Off)
        handler.removeCallbacks(expireTravel)
        if (detector != null) {
            stopLocationUpdates()
            detector = null
        }
        manualSpeed = false
        unregisterReceiver(screenReceiver)
        settings.preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        if (BuildConfig.ACCESSIBILITY_OVERLAY) AnchorAccessibilityService.connectionListener = null
        detachOverlay()
        stopForeground(true)
        super.onDestroy()
    }

    // Updates were only requested while the permission was granted; if it was revoked since,
    // Android already dropped them and removeUpdates has nothing to do.
    @SuppressLint("MissingPermission")
    private fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: RuntimeException) {
        }
    }

    /**
     * Returns true when a manual session may follow GPS speed: location permission is granted
     * and the foreground service carries the location type, which keeps GPS working while the
     * app itself is in the background under the overlay. Android 14 and later refuse that type
     * when the service restarts from the background, so the session then runs on sensors only.
     */
    private fun startForegroundForSession(automatic: Boolean): Boolean {
        ensureNotificationChannel()
        val content = notification()
        val location = automatic || (settings.appearance.forwardMotion && hasPreciseLocation(this))
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(NOTIFICATION_ID, content)
            return !automatic && location
        }
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (!location) {
            startForeground(NOTIFICATION_ID, content, special)
            return false
        }
        try {
            startForeground(NOTIFICATION_ID, content, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (error: SecurityException) {
            if (automatic) throw error
            startForeground(NOTIFICATION_ID, content, special)
            return false
        }
        return !automatic
    }

    private fun notificationManager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val name = localized().getString(R.string.channel_name)
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
    }

    private fun refreshNotification() {
        ensureNotificationChannel()
        notificationManager().notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val ui = localized()
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        )
        val stop = PendingIntent.getBroadcast(
            this, 1, Intent(this, StopOverlayReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_status)
            .setContentTitle(ui.getString(R.string.app_name))
            .setContentText(ui.getString(if (state == SessionState.Waiting) R.string.status_waiting else R.string.status_active))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, ui.getString(R.string.action_stop), stop)
            .build()
    }

    private fun setState(value: SessionState) {
        if (state == value) return
        state = value
        for (listener in listeners.toTypedArray()) listener()
        if (Build.VERSION.SDK_INT >= 24) {
            TileService.requestListeningState(this, ComponentName(this, AnchorTileService::class.java))
        }
    }

    companion object {
        private const val CHANNEL = "motion_anchor_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val RESUME_NOTIFICATION_ID = 1002
        internal const val EXTRA_AUTOMATIC = "app.motionanchor.AUTOMATIC"
        private const val GPS_PRIORITY_MS = 10_000L

        var state: SessionState = SessionState.Off
            private set
        /** True while the running session waits for or follows a detected trip. */
        var tripMode = false
            private set
        val listeners = mutableSetOf<() -> Unit>()

        /**
         * The connected accessibility service, or null. The standard flavor does not declare the
         * service, and the constant check lets R8 drop the class from that APK entirely.
         */
        fun accessibilityHost(): AccessibilityService? =
            if (BuildConfig.ACCESSIBILITY_OVERLAY) AnchorAccessibilityService.current else null

        fun canDrawOverApps(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(context)
            return true
        }

        fun canShowOverlay(context: Context): Boolean =
            canDrawOverApps(context) || (AnchorSettings(context).fullScreen &&
                Build.VERSION.SDK_INT >= 22 && accessibilityHost() != null)

        fun hasPreciseLocation(context: Context): Boolean =
            context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Location access while no activity is visible, needed to start a trip session at boot. */
        fun hasBackgroundLocation(context: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
            context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 23) return true
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return power.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun isGpsEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return try {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (_: RuntimeException) {
                false
            }
        }

        fun start(context: Context, automatic: Boolean): Boolean {
            if (!canShowOverlay(context)) return false
            val intent = Intent(context, MotionAnchorService::class.java).putExtra(EXTRA_AUTOMATIC, automatic)
            return try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                true
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }

        /**
         * Ends the session. A stop the user asked for also ends background mode, otherwise the
         * next boot would bring back a session they just switched off.
         */
        fun stop(context: Context, keepBackground: Boolean = false) {
            if (!keepBackground) {
                val settings = AnchorSettings(context)
                if (settings.backgroundTrip) {
                    settings.preferences.edit().putBoolean(AnchorSettings.BACKGROUND_TRIP, false).apply()
                }
            }
            context.stopService(Intent(context, MotionAnchorService::class.java))
        }

        /**
         * Starts background trip detection where Android allows it without a visible activity,
         * and otherwise posts a notification that resumes it with one tap.
         */
        fun resumeInBackground(context: Context) {
            if (!AnchorSettings(context).backgroundTrip || state != SessionState.Off) return
            if (canShowOverlay(context) && hasPreciseLocation(context) && hasBackgroundLocation(context) &&
                start(context, automatic = true)
            ) return
            postResumeNotification(context)
        }

        private fun postResumeNotification(context: Context) {
            val ui = context.localized()
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, ui.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) },
                )
            }
            val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val resume = PendingIntent.getActivity(
                context, 3,
                Intent(context, TileActionActivity::class.java).putExtra(EXTRA_AUTOMATIC, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or immutable,
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL)
            } else {
                Notification.Builder(context)
            }
            val text = ui.getString(R.string.resume_text)
            val notification = builder.setSmallIcon(R.drawable.ic_status)
                .setContentTitle(ui.getString(R.string.resume_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(resume)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(RESUME_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }
    }
}
