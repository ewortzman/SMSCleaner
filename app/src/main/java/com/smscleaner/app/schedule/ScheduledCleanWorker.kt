package com.smscleaner.app.schedule

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.smscleaner.app.CleanerConfig
import com.smscleaner.app.ContactResolver
import com.smscleaner.app.DeleteOrder
import com.smscleaner.app.MessageCleaner
import com.smscleaner.app.R
import com.smscleaner.app.SMSCleanerApplication
import com.smscleaner.app.model.RecurrenceType
import java.util.Calendar

class ScheduledCleanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var deletedCount = 0
    private var lastStage = ""

    override suspend fun doWork(): Result {
        val prefs = SchedulePreferences(applicationContext)
        val config = prefs.load()

        if (!config.enabled) return Result.success()
        if (!shouldRunToday(config, prefs)) return Result.success()

        // Check if app is default SMS
        val roleManager = applicationContext.getSystemService(android.app.role.RoleManager::class.java)
        if (!roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)) {
            showReminderNotification()
            return Result.success()
        }

        setForeground(createForegroundInfo("Starting scheduled cleanup..."))

        val cutoffDate = computeCutoffDate(config.deleteOlderThanMonths)

        val cleanerConfig = CleanerConfig(
            startDateMs = null,
            endDateMs = cutoffDate,
            includeSms = config.includeSms,
            includeMmsMedia = config.includeMmsMedia,
            includeMmsGroup = config.includeMmsGroup,
            includeRcs = config.includeRcs,
            batchSize = config.batchSize,
            batchSizeMmsMedia = config.batchSizeMmsMedia,
            batchSizeMmsGroup = config.batchSizeMmsGroup,
            deleteChunkSize = config.deleteChunkSize,
            delayMs = config.delayMs,
            dryRun = false,
            deleteOrder = DeleteOrder.OLDEST_FIRST
        )

        val resolver = applicationContext.contentResolver
        val contactResolver = ContactResolver(resolver)
        val cleaner = MessageCleaner(resolver, contactResolver, cleanerConfig) { line ->
            parseProgress(line)
            updateNotification("$lastStage\n$deletedCount messages deleted")
        }

        return try {
            val count = cleaner.execute()
            prefs.setLastRunMs(System.currentTimeMillis())
            showCompletionNotification(count)
            Result.success()
        } catch (_: kotlinx.coroutines.CancellationException) {
            showCompletionNotification(deletedCount, cancelled = true)
            Result.failure()
        } catch (e: Exception) {
            showErrorNotification(e.message ?: "Unknown error")
            Result.failure()
        }
    }

    private fun shouldRunToday(config: com.smscleaner.app.model.ScheduleConfig, prefs: SchedulePreferences): Boolean {
        val today = Calendar.getInstance()
        return when (config.recurrenceType) {
            RecurrenceType.WEEKLY -> today.get(Calendar.DAY_OF_WEEK) == config.dayOfWeek
            RecurrenceType.BIWEEKLY -> {
                if (today.get(Calendar.DAY_OF_WEEK) != config.dayOfWeek) return false
                val lastRun = prefs.getLastRunMs()
                if (lastRun == 0L) return true
                val daysSinceLastRun = (System.currentTimeMillis() - lastRun) / (24 * 60 * 60 * 1000)
                daysSinceLastRun >= 13
            }
            RecurrenceType.MONTHLY -> {
                val targetDay = config.dayOfMonth.coerceAtMost(today.getActualMaximum(Calendar.DAY_OF_MONTH))
                today.get(Calendar.DAY_OF_MONTH) == targetDay
            }
        }
    }

    private fun computeCutoffDate(months: Int): Long {
        return Calendar.getInstance().apply {
            add(Calendar.MONTH, -months)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun parseProgress(line: String) {
        if (line.contains("===")) {
            lastStage = line.replace(Regex("\\[.*?\\]\\s*"), "").trim()
        }
        val match = Regex("(\\d+) deleted").find(line)
        if (match != null) {
            deletedCount = match.groupValues[1].toIntOrNull() ?: deletedCount
        }
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, SMSCleanerApplication.CHANNEL_CLEAN_PROGRESS)
            .setContentTitle("SMS Cleaner")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID_PROGRESS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(applicationContext, SMSCleanerApplication.CHANNEL_CLEAN_PROGRESS)
            .setContentTitle("SMS Cleaner — Running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun showReminderNotification() {
        val notification = NotificationCompat.Builder(applicationContext, SMSCleanerApplication.CHANNEL_CLEAN_REMINDER)
            .setContentTitle("SMS Cleaner — Action Required")
            .setContentText("Set SMS Cleaner as default SMS app to run scheduled cleanup")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_REMINDER, notification)
    }

    private fun showCompletionNotification(count: Int, cancelled: Boolean = false) {
        val title = if (cancelled) "SMS Cleaner — Cancelled" else "SMS Cleaner — Complete"
        val text = "$count messages deleted"

        val notification = NotificationCompat.Builder(applicationContext, SMSCleanerApplication.CHANNEL_CLEAN_REMINDER)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    private fun showErrorNotification(error: String) {
        val notification = NotificationCompat.Builder(applicationContext, SMSCleanerApplication.CHANNEL_CLEAN_REMINDER)
            .setContentTitle("SMS Cleaner — Error")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    companion object {
        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val NOTIFICATION_ID_REMINDER = 1002
        private const val NOTIFICATION_ID_COMPLETE = 1003
    }
}
