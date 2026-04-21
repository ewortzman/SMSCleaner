package com.smscleaner.app.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smscleaner.app.model.ScheduleConfig
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ScheduleManager(private val context: Context) {

    private val prefs = SchedulePreferences(context)

    fun enableSchedule(config: ScheduleConfig) {
        prefs.save(config.copy(enabled = true))
        scheduleWork(config)
    }

    fun disableSchedule() {
        val config = prefs.load()
        prefs.save(config.copy(enabled = false))
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun isEnabled(): Boolean = prefs.load().enabled

    fun getConfig(): ScheduleConfig = prefs.load()

    fun getNextRunDescription(): String? {
        val config = prefs.load()
        if (!config.enabled) return null

        val next = computeNextRun(config) ?: return null
        val fmt = java.text.SimpleDateFormat("EEE, MMM dd 'at' h:mm a", java.util.Locale.US)
        return fmt.format(java.util.Date(next))
    }

    private fun scheduleWork(config: ScheduleConfig) {
        val initialDelay = computeInitialDelay(config)

        val request = PeriodicWorkRequestBuilder<ScheduledCleanWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun computeInitialDelay(config: ScheduleConfig): Long {
        val now = System.currentTimeMillis()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, config.hour)
            set(Calendar.MINUTE, config.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now
    }

    private fun computeNextRun(config: ScheduleConfig): Long? {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, config.hour)
            set(Calendar.MINUTE, config.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        when (config.recurrenceType) {
            com.smscleaner.app.model.RecurrenceType.WEEKLY -> {
                while (cal.get(Calendar.DAY_OF_WEEK) != config.dayOfWeek || cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            com.smscleaner.app.model.RecurrenceType.BIWEEKLY -> {
                val lastRun = prefs.getLastRunMs()
                while (cal.get(Calendar.DAY_OF_WEEK) != config.dayOfWeek || cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (lastRun > 0 && cal.timeInMillis - lastRun < 13 * 24 * 60 * 60 * 1000L) {
                    cal.add(Calendar.DAY_OF_YEAR, 7)
                }
            }
            com.smscleaner.app.model.RecurrenceType.MONTHLY -> {
                cal.set(Calendar.DAY_OF_MONTH, config.dayOfMonth.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.MONTH, 1)
                    cal.set(Calendar.DAY_OF_MONTH, config.dayOfMonth.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                }
            }
        }
        return cal.timeInMillis
    }

    companion object {
        const val WORK_NAME = "scheduled_clean"
    }
}
