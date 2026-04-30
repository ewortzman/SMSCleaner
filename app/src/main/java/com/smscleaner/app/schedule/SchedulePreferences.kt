package com.smscleaner.app.schedule

import android.content.Context
import com.smscleaner.app.model.RecurrenceType
import com.smscleaner.app.model.ScheduleConfig

class SchedulePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("schedule_prefs", Context.MODE_PRIVATE)

    fun save(config: ScheduleConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putString("recurrence_type", config.recurrenceType.name)
            .putInt("day_of_week", config.dayOfWeek)
            .putInt("day_of_month", config.dayOfMonth)
            .putInt("hour", config.hour)
            .putInt("minute", config.minute)
            .putInt("delete_older_than_months", config.deleteOlderThanMonths)
            .putBoolean("include_sms", config.includeSms)
            .putBoolean("include_mms_media", config.includeMmsMedia)
            .putBoolean("include_mms_group", config.includeMmsGroup)
            .putBoolean("include_rcs", config.includeRcs)
            .putInt("batch_size", config.batchSize)
            .putInt("batch_size_mms_media", config.batchSizeMmsMedia)
            .putInt("batch_size_mms_group", config.batchSizeMmsGroup)
            .putInt("delete_chunk_size", config.deleteChunkSize)
            .putLong("delay_ms", config.delayMs)
            .putBoolean("requires_charging", config.requiresCharging)
            .putLong("last_run_ms", prefs.getLong("last_run_ms", 0))
            .apply()
    }

    fun load(): ScheduleConfig {
        return ScheduleConfig(
            enabled = prefs.getBoolean("enabled", false),
            recurrenceType = try {
                RecurrenceType.valueOf(prefs.getString("recurrence_type", "MONTHLY")!!)
            } catch (_: Exception) { RecurrenceType.MONTHLY },
            dayOfWeek = prefs.getInt("day_of_week", 1),
            dayOfMonth = prefs.getInt("day_of_month", 1),
            hour = prefs.getInt("hour", 2),
            minute = prefs.getInt("minute", 0),
            deleteOlderThanMonths = prefs.getInt("delete_older_than_months", 24),
            includeSms = prefs.getBoolean("include_sms", true),
            includeMmsMedia = prefs.getBoolean("include_mms_media", true),
            includeMmsGroup = prefs.getBoolean("include_mms_group", true),
            includeRcs = prefs.getBoolean("include_rcs", false),
            batchSize = prefs.getInt("batch_size", 1000),
            batchSizeMmsMedia = prefs.getInt("batch_size_mms_media", 100),
            batchSizeMmsGroup = prefs.getInt("batch_size_mms_group", 500),
            deleteChunkSize = prefs.getInt("delete_chunk_size", 50),
            delayMs = prefs.getLong("delay_ms", 100),
            requiresCharging = prefs.getBoolean("requires_charging", true)
        )
    }

    fun getLastRunMs(): Long = prefs.getLong("last_run_ms", 0)

    fun setLastRunMs(ms: Long) {
        prefs.edit().putLong("last_run_ms", ms).apply()
    }
}
