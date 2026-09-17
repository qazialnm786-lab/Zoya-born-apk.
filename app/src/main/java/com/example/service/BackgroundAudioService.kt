package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.WakeWordDetector
import com.example.live.LiveSessionManager
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BackgroundAudioService : Service() {

    companion object {
        private const val TAG = "BackgroundAudioService"
        const val CHANNEL_ID = "zoya_live_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.example.zoya.ACTION_START"
        const val ACTION_STOP = "com.example.zoya.ACTION_STOP"
        const val ACTION_WAKE_UP = "com.example.zoya.ACTION_WAKE_UP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        var currentServiceInstance: BackgroundAudioService? = null
            private set
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    lateinit var toolEngine: ToolExecutionEngine
        private set
    lateinit var liveSessionManager: LiveSessionManager
        private set
    private var wakeWordDetector: WakeWordDetector? = null

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundAudioService = this@BackgroundAudioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        currentServiceInstance = this
        createNotificationChannel()

        toolEngine = ToolExecutionEngine(this)
        liveSessionManager = LiveSessionManager(this, serviceScope, toolEngine)

        // Setup local wake-word detector for "Zoya"
        wakeWordDetector = WakeWordDetector(
            context = this,
            onWakeWordDetected = {
                onWakeWordTriggered()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_WAKE_UP -> {
                onWakeWordTriggered()
            }
            else -> {
                startForegroundServiceWithNotification()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildPersistentNotification("Listening for \"Zoya\" in the background")
        startForeground(NOTIFICATION_ID, notification)
        _isRunning.value = true

        // Start wake-word detector
        wakeWordDetector?.start(serviceScope)
    }

    private fun onWakeWordTriggered() {
        Log.i(TAG, "Wake-word 'Zoya' triggered in background service!")
        vibrateHaptic()

        // Update persistent notification to show Zoya is active
        val notification = buildPersistentNotification("Zoya awakened! Listening to your voice...")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)

        // Awaken Live Session
        serviceScope.launch {
            liveSessionManager.startLiveSession()
        }

        // Bring MainActivity to the front if appropriate
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching activity on wake word: ${e.message}")
        }
    }

    private fun vibrateHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vm?.defaultVibrator
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 100), -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 100), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 80, 60, 100), -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic vibration error: ${e.message}")
        }
    }

    private fun buildPersistentNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeUpIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BackgroundAudioService::class.java).apply { action = ACTION_WAKE_UP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BackgroundAudioService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zoya Assistant")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Wake Up", wakeUpIntent)
            .addAction(0, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zoya Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Zoya listening for wake-word and managing live voice sessions in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        _isRunning.value = false
        wakeWordDetector?.stop()
        liveSessionManager.stopLiveSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        currentServiceInstance = null
        _isRunning.value = false
        wakeWordDetector?.stop()
        liveSessionManager.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
