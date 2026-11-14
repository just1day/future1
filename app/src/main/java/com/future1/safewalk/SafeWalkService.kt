package com.future1.safewalk

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.future1.safewalk.core.Decision
import com.future1.safewalk.core.GeoPoint
import com.future1.safewalk.core.Level
import com.future1.safewalk.core.SafetyEngine
import com.future1.safewalk.core.Snapshot
import com.future1.safewalk.core.WalkingDetector

/**
 * 항상 실행되는 보호 서비스.
 * 걸음 센서 + GPS로 걷는 중인지, 화면을 쓰고 있는지, 횡단보도 근처인지를 0.5초마다 확인하고
 * 위험하면 [LockOverlay]로 화면을 덮는다.
 */
class SafeWalkService : Service(), SensorEventListener, LocationListener {

    private val handler = Handler(Looper.getMainLooper())
    private val detector = WalkingDetector()
    private val crosswalks = CrosswalkRepository()
    private lateinit var engine: SafetyEngine
    private lateinit var overlay: LockOverlay
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager

    private var lastLocation: Location? = null
    private var lastLevelLocked: Level? = null
    private var suspendedUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = SafetyEngine(Prefs(this).config())
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        overlay = LockOverlay(this) {
            // 긴급전화를 누르면 2분 동안 잠금을 멈춘다
            suspendedUntil = SystemClock.elapsedRealtime() + EMERGENCY_SUSPEND_MS
            overlay.hide()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD) {
            engine.config = Prefs(this).config()
            return START_STICKY
        }
        try {
            startInForeground()
        } catch (e: Exception) {
            // 위치 권한이 없으면 Android 14+에서 위치 타입 포그라운드 서비스를 시작할 수 없다
            Log.e(TAG, "cannot start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startSensors()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        overlay.hide()
        crosswalks.shutdown()
        super.onDestroy()
    }

    // ---- 판단 ----

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        if (now < suspendedUntil) return

        val loc = lastLocation?.takeIf { now - it.elapsedRealtimeNanos / 1_000_000 < LOCATION_STALE_MS }
        val here = loc?.let { GeoPoint(it.latitude, it.longitude) }
        val snapshot = Snapshot(
            nowMs = now,
            walking = detector.isWalking(now),
            screenInUse = power.isInteractive && !keyguard.isKeyguardLocked,
            distanceToCrosswalkM = here?.let { crosswalks.index.nearestDistanceMeters(it) },
            locationAccuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
        )
        apply(engine.evaluate(snapshot))
    }

    private fun apply(d: Decision) {
        if (d.locked) {
            if (lastLevelLocked != d.level) vibrate(d.level)
            overlay.show(d)
            lastLevelLocked = d.level
        } else if (overlay.isShowing) {
            overlay.hide()
            lastLevelLocked = null
        }
    }

    private fun vibrate(level: Level) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = if (level == Level.DANGER) longArrayOf(0, 300, 150, 300, 150, 300) else longArrayOf(0, 250)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // ---- 센서 ----

    private fun startSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.unregisterListener(this)
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: Log.w(TAG, "no step detector; using GPS speed only")

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(this)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 5f, this, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission missing", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) detector.onStep(SystemClock.elapsedRealtime())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        // GPS가 더 정확하면 네트워크 위치로 덮어쓰지 않는다
        val prev = lastLocation
        val now = SystemClock.elapsedRealtime()
        val prevFresh = prev != null && now - prev.elapsedRealtimeNanos / 1_000_000 < 3_000
        if (prevFresh && prev!!.provider == LocationManager.GPS_PROVIDER &&
            location.provider != LocationManager.GPS_PROVIDER
        ) return

        lastLocation = location
        detector.onLocation(
            now,
            location.takeIf { it.hasSpeed() }?.speed?.toDouble(),
            location.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
        )
        crosswalks.maybeRefresh(GeoPoint(location.latitude, location.longitude))
    }

    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = startSensors()
    override fun onProviderDisabled(provider: String) = Unit

    // ---- 알림 ----

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SafeWalkService"
        private const val CHANNEL_ID = "safewalk"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 500L
        private const val LOCATION_STALE_MS = 15_000L
        private const val EMERGENCY_SUSPEND_MS = 120_000L
        const val ACTION_RELOAD = "com.future1.safewalk.RELOAD"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SafeWalkService::class.java))
        }

        fun reload(context: Context) {
            context.startService(Intent(context, SafeWalkService::class.java).setAction(ACTION_RELOAD))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SafeWalkService::class.java))
        }
    }
}
