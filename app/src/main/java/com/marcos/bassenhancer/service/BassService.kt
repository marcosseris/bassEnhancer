package com.marcos.bassenhancer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.marcos.bassenhancer.MainActivity
import com.marcos.bassenhancer.R
import com.marcos.bassenhancer.core.AudioSource
import com.marcos.bassenhancer.core.BassAnalyzer
import com.marcos.bassenhancer.core.CaptureMode
import com.marcos.bassenhancer.core.HapticEngine
import com.marcos.bassenhancer.core.PlaybackCaptureSource
import com.marcos.bassenhancer.core.Prefs
import com.marcos.bassenhancer.core.PrefsRepository
import com.marcos.bassenhancer.core.VisualizerSource

private const val TAG = "BassService"
private const val CHANNEL_ID = "bass_enhancer_running"
private const val NOTIFICATION_ID = 41

/**
 * Foreground service that owns the capture -> analysis -> actuator chain.
 *
 * Two threads do the work: an audio thread blocked on AudioRecord.read, and a
 * haptic thread ticking at the user's update interval. Everything else is idle.
 */
class BassService : Service() {

    private lateinit var prefs: PrefsRepository
    private lateinit var haptics: HapticEngine

    private var audioThread: Thread? = null
    private var source: AudioSource? = null
    private var analyzer: BassAnalyzer? = null
    private var projection: MediaProjection? = null

    private var hapticThread: HandlerThread? = null
    private var hapticHandler: Handler? = null

    @Volatile
    private var running = false

    @Volatile
    private var screenOn = true

    @Volatile
    private var settings: Prefs = Prefs()

    private var lastMeterPush = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection revoked")
            stopWithMessage(getString(R.string.err_projection_stopped))
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            if (!screenOn && settings.pauseOnScreenOff) haptics.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsRepository.get(this)
        settings = prefs.current
        haptics = HapticEngine(this)
        createChannel()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.update { it.copy(enabled = false) }
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SETTINGS_CHANGED -> {
                settings = prefs.current
                analyzer?.configure(settings)
                updateNotification()
                return START_STICKY
            }
        }

        settings = prefs.current
        startForegroundCompat()

        if (running) {
            analyzer?.configure(settings)
            return START_STICKY
        }

        val started = when (settings.captureMode) {
            CaptureMode.PLAYBACK -> startPlaybackCapture(intent)
            CaptureMode.VISUALIZER -> startVisualizer()
        }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startPlaybackCapture(intent: Intent?): Boolean {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            stopWithMessage(getString(R.string.err_no_consent))
            return false
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (mp == null) {
            stopWithMessage(getString(R.string.err_no_consent))
            return false
        }
        projection = mp
        // Android 14 insists a callback is registered before capture begins.
        mp.registerCallback(projectionCallback, Handler(mainLooper))

        return startPipeline(PlaybackCaptureSource(mp))
    }

    private fun startVisualizer(): Boolean = startPipeline(VisualizerSource())

    private fun startPipeline(src: AudioSource): Boolean {
        if (!haptics.isPresent) {
            stopWithMessage(getString(R.string.err_no_vibrator))
            return false
        }

        source = src
        val an = BassAnalyzer(src.sampleRate).apply { configure(settings) }
        analyzer = an

        val ht = HandlerThread("bass-haptics", Process.THREAD_PRIORITY_URGENT_AUDIO)
        ht.start()
        hapticThread = ht
        hapticHandler = Handler(ht.looper)

        running = true
        ServiceState.set(RunState.RUNNING)
        updateNotification()
        scheduleHapticTick()

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                src.run { buf, n -> an.process(buf, n, src.channels) }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                stopWithMessage(e.message ?: getString(R.string.err_capture))
            }
        }, "bass-capture")
        thread.isDaemon = true
        audioThread = thread
        thread.start()
        return true
    }

    private fun scheduleHapticTick() {
        val handler = hapticHandler ?: return
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                val s = settings
                tick(s)
                handler.postDelayed(this, s.updateIntervalMs.toLong().coerceIn(8L, 200L))
            }
        })
    }

    private fun tick(s: Prefs) {
        val an = analyzer ?: return

        if (isSuppressed(s)) {
            haptics.stop()
            an.pendingKick = 0f
            pushMeter(an.levelDb, 0f)
            return
        }

        val kick = an.pendingKick
        if (kick > 0f) an.pendingKick = 0f
        val level = an.level
        haptics.render(level, kick, s)
        pushMeter(an.levelDb, level)
    }

    /** Cheap, per-tick reasons to hold the actuator still. */
    private fun isSuppressed(s: Prefs): Boolean {
        if (s.pauseOnScreenOff && !screenOn) return true
        if (s.pauseInCall) {
            val am = getSystemService(AudioManager::class.java)
            val mode = am?.mode
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                return true
            }
        }
        if (s.batteryFloorPct > 0 && batteryPct() in 0 until s.batteryFloorPct) return true
        return false
    }

    private var lastBatteryCheck = 0L
    private var cachedBattery = 100

    private fun batteryPct(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - lastBatteryCheck > 60_000L) {
            lastBatteryCheck = now
            cachedBattery = getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        }
        return cachedBattery
    }

    /** Throttled so the UI recomposes ~10x a second instead of 30. */
    private fun pushMeter(db: Float, amplitude: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMeterPush < 90L) return
        lastMeterPush = now
        ServiceState.publishLevels(db, amplitude)
    }

    private fun stopWithMessage(message: String) {
        ServiceState.set(RunState.ERROR, message)
        prefs.update { it.copy(enabled = false) }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        haptics.stop()
        source?.stop()
        audioThread?.interrupt()
        audioThread = null
        hapticHandler?.removeCallbacksAndMessages(null)
        hapticThread?.quitSafely()
        hapticThread = null
        hapticHandler = null
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        analyzer = null
        runCatching { unregisterReceiver(screenReceiver) }
        if (ServiceState.state.value != RunState.ERROR) ServiceState.set(RunState.STOPPED)
        BassTileService.requestUpdate(this)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val type = if (settings.captureMode == CaptureMode.PLAYBACK) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
        BassTileService.requestUpdate(this)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BassService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                getString(
                    R.string.notif_text,
                    settings.lowCutHz,
                    settings.highCutHz,
                ),
            )
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.action_stop), stop).build(),
            )
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.marcos.bassenhancer.STOP"
        const val ACTION_SETTINGS_CHANGED = "com.marcos.bassenhancer.SETTINGS_CHANGED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun startWithProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, BassService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun startVisualizerMode(context: Context) {
            context.startForegroundService(Intent(context, BassService::class.java))
        }

        fun notifySettingsChanged(context: Context) {
            if (ServiceState.state.value != RunState.RUNNING) return
            context.startService(
                Intent(context, BassService::class.java).setAction(ACTION_SETTINGS_CHANGED),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BassService::class.java))
        }
    }
}
