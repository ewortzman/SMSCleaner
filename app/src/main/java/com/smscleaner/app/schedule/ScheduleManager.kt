package com.smscleaner.app.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smscleaner.app.model.RecurrenceType
import com.smscleaner.app.model.ScheduleConfig
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ScheduleManager(private val context: Context) {

    private val prefs = SchedulePreferences(context)

    fun getAll(): List<ScheduleConfig> = prefs.loadAll()

    fun getById(id: String): ScheduleConfig? = prefs.loadById(id)

    fun upsert(config: ScheduleConfig) {
        prefs.upsert(config)
        if (config.enabled) scheduleWork(config) else cancelWork(config.id)
    }

    fun delete(id: String) {
        cancelWork(id)
        prefs.remove(id)
    }

    fun getNextRunDescription(config: ScheduleConfig): String? {
        if (!config.enabled) return null
        val next = computeNextRun(config)
        val fmt = java.text.SimpleDateFormat("EEE, MMM dd 'at' h:mm a", java.util.Locale.US)
        return fmt.format(java.util.Date(next))
    }

    private fun scheduleWork(config: ScheduleConfig) {
        val initialDelay = computeInitialDelay(config)

        val constraints = Constraints.Builder()
            .setRequiresCharging(config.requiresCharging)
            .build()

        val inputData = Data.Builder()
            .putString(KEY_SCHEDULE_ID, config.id)
            .build()

        val request = PeriodicWorkRequestBuilder<ScheduledCleanWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workNameFor(config.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun cancelWork(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workNameFor(id))
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

    private fun computeNextRun(config: ScheduleConfig): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, config.hour)
            set(Calendar.MINUTE, config.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        when (config.recurrenceType) {
            RecurrenceType.WEEKLY -> {
                while (cal.get(Calendar.DAY_OF_WEEK) != config.dayOfWeek || cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            RecurrenceType.BIWEEKLY -> {
                while (cal.get(Calendar.DAY_OF_WEEK) != config.dayOfWeek || cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                if (config.lastRunMs > 0 && cal.timeInMillis - config.lastRunMs < 13 * 24 * 60 * 60 * 1000L) {
                    cal.add(Calendar.DAY_OF_YEAR, 7)
                }
            }
            RecurrenceType.MONTHLY -> {
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
        const val KEY_SCHEDULE_ID = "schedule_id"
        fun workNameFor(id: String): String = "scheduled_clean_$id"
    }
}
