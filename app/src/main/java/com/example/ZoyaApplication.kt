package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.service.BackgroundAudioService

class ZoyaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BackgroundAudioService.CHANNEL_ID,
                "Zoya Live Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background wake-word service for Zoya"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
