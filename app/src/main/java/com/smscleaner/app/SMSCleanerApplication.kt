package com.smscleaner.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SMSCleanerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val cleanChannel = NotificationChannel(
            CHANNEL_CLEAN_PROGRESS,
            "Cleanup Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress during scheduled message cleanup"
        }

        val reminderChannel = NotificationChannel(
            CHANNEL_CLEAN_REMINDER,
            "Cleanup Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminds you to run scheduled message cleanup"
        }

        manager.createNotificationChannel(cleanChannel)
        manager.createNotificationChannel(reminderChannel)
    }

    companion object {
        const val CHANNEL_CLEAN_PROGRESS = "clean_progress"
        const val CHANNEL_CLEAN_REMINDER = "clean_reminder"
    }
}
