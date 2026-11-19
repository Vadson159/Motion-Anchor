// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package app.motionanchor.motion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.WindowManager
import app.motionanchor.AnchorSettings
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

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AnchorSettings.FULL_SCREEN) syncOverlay()
        updateAppearance()
        if (key == AnchorSettings.LANGUAGE && active) refreshNotification()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val travel = detector ?: return
            val speed = location.speed.takeIf { location.hasSpeed() && location.accuracy <= 50f }
            showWhenMoving(travel.update(SystemClock.elapsedRealtime(), speed))
        }
        override fun onProviderDisabled(provider: String) {
            if (detector != null) detector = TravelDetector()
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
        settings.preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        AnchorAccessibilityService.connectionListener = ::syncOverlay
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canShowOverlay(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return if (detector != null) START_NOT_STICKY else START_STICKY

        val automatic = intent?.getBooleanExtra(EXTRA_AUTOMATIC, false) == true
        if (automatic && (!hasPreciseLocation(this) || !isGpsEnabled(this))) {
            stopSelf()
            return START_NOT_STICKY
        }
        setState(if (automatic) SessionState.Waiting else SessionState.Showing)
        try {
            startForegroundForSession(automatic)
            active = true
            if (automatic) {
                detector = TravelDetector()
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener)
                handler.post(expireTravel)
            } else {
                wantsOverlay = true
                syncOverlay()
            }
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: IllegalStateException) {
            stopSelf()
        }
        return if (automatic) START_NOT_STICKY else START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (active) refreshNotification()
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
        val accessibility = AnchorAccessibilityService.current
            .takeIf { settings.fullScreen && Build.VERSION.SDK_INT >= 22 }
        val host: Context = accessibility ?: this
        if (overlayHost !== host || !wantsOverlay) detachOverlay()
        if (!wantsOverlay || overlayView != null) return

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
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
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

    private var imageRevision: Int? = null
    private var marker: android.graphics.Bitmap? = null

    private fun updateAppearance() {
        val view = overlayView ?: return
        val appearance = settings.appearance
        val revision = settings.preferences.getInt(AnchorSettings.IMAGE_REVISION, 0)
        if (appearance.style == CueStyle.Image) {
            if (imageRevision != revision) {
                marker = MarkerImage.load(this)
                imageRevision = revision
            }
        } else {
            marker = null
            imageRevision = null
        }
        view.setAppearance(appearance, marker)
    }

    private fun detachOverlay() {
        overlayView?.let { view -> runCatching { overlayManager?.removeViewImmediate(view) } }
        overlayView = null
        overlayHost = null
        overlayManager = null
        if (drawing) {
            motionEstimator.stop()
            drawing = false
        }
    }

    override fun onDestroy() {
        active = false
        setState(SessionState.Off)
        handler.removeCallbacks(expireTravel)
        if (detector != null) {
            runCatching { locationManager.removeUpdates(locationListener) }
            detector = null
        }
        settings.preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        AnchorAccessibilityService.connectionListener = null
        detachOverlay()
        stopForeground(true)
        super.onDestroy()
    }

    private fun startForegroundForSession(automatic: Boolean) {
        ensureNotificationChannel()
        val content = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = if (automatic) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(NOTIFICATION_ID, content, type)
        } else {
            startForeground(NOTIFICATION_ID, content)
        }
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
        listeners.toList().forEach { it() }
        if (Build.VERSION.SDK_INT >= 24) {
            TileService.requestListeningState(this, ComponentName(this, AnchorTileService::class.java))
        }
    }

    companion object {
        private const val CHANNEL = "motion_anchor_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_AUTOMATIC = "app.motionanchor.AUTOMATIC"

        var state: SessionState = SessionState.Off
            private set
        val listeners = mutableSetOf<() -> Unit>()

        fun canDrawOverApps(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(context)
            return true
        }

        fun canShowOverlay(context: Context): Boolean =
            canDrawOverApps(context) || (AnchorSettings(context).fullScreen &&
                Build.VERSION.SDK_INT >= 22 && AnchorAccessibilityService.current != null)

        fun hasPreciseLocation(context: Context): Boolean =
            context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun isGpsEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
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

        fun stop(context: Context) {
            context.stopService(Intent(context, MotionAnchorService::class.java))
        }
    }
}
