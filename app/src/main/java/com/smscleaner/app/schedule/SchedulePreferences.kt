package com.smscleaner.app.schedule

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smscleaner.app.model.RecurrenceType
import com.smscleaner.app.model.ScheduleConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.scheduleDataStore by preferencesDataStore(name = "schedule_prefs")

class SchedulePreferences(private val context: Context) {

    private val KEY_ENABLED = booleanPreferencesKey("enabled")
    private val KEY_RECURRENCE = stringPreferencesKey("recurrence_type")
    private val KEY_DAY_OF_WEEK = intPreferencesKey("day_of_week")
    private val KEY_DAY_OF_MONTH = intPreferencesKey("day_of_month")
    private val KEY_HOUR = intPreferencesKey("hour")
    private val KEY_MINUTE = intPreferencesKey("minute")
    private val KEY_DELETE_OLDER_THAN_MONTHS = intPreferencesKey("delete_older_than_months")
    private val KEY_INCLUDE_SMS = booleanPreferencesKey("include_sms")
    private val KEY_INCLUDE_MMS_MEDIA = booleanPreferencesKey("include_mms_media")
    private val KEY_INCLUDE_MMS_GROUP = booleanPreferencesKey("include_mms_group")
    private val KEY_INCLUDE_RCS = booleanPreferencesKey("include_rcs")
    private val KEY_BATCH_SIZE = intPreferencesKey("batch_size")
    private val KEY_BATCH_SIZE_MMS_MEDIA = intPreferencesKey("batch_size_mms_media")
    private val KEY_BATCH_SIZE_MMS_GROUP = intPreferencesKey("batch_size_mms_group")
    private val KEY_DELETE_CHUNK_SIZE = intPreferencesKey("delete_chunk_size")
    private val KEY_DELAY_MS = longPreferencesKey("delay_ms")
    private val KEY_REQUIRES_CHARGING = booleanPreferencesKey("requires_charging")
    private val KEY_LAST_RUN_MS = longPreferencesKey("last_run_ms")

    fun save(config: ScheduleConfig) = runBlocking {
        context.scheduleDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = config.enabled
            prefs[KEY_RECURRENCE] = config.recurrenceType.name
            prefs[KEY_DAY_OF_WEEK] = config.dayOfWeek
            prefs[KEY_DAY_OF_MONTH] = config.dayOfMonth
            prefs[KEY_HOUR] = config.hour
            prefs[KEY_MINUTE] = config.minute
            prefs[KEY_DELETE_OLDER_THAN_MONTHS] = config.deleteOlderThanMonths
            prefs[KEY_INCLUDE_SMS] = config.includeSms
            prefs[KEY_INCLUDE_MMS_MEDIA] = config.includeMmsMedia
            prefs[KEY_INCLUDE_MMS_GROUP] = config.includeMmsGroup
            prefs[KEY_INCLUDE_RCS] = config.includeRcs
            prefs[KEY_BATCH_SIZE] = config.batchSize
            prefs[KEY_BATCH_SIZE_MMS_MEDIA] = config.batchSizeMmsMedia
            prefs[KEY_BATCH_SIZE_MMS_GROUP] = config.batchSizeMmsGroup
            prefs[KEY_DELETE_CHUNK_SIZE] = config.deleteChunkSize
            prefs[KEY_DELAY_MS] = config.delayMs
            prefs[KEY_REQUIRES_CHARGING] = config.requiresCharging
        }
    }

    fun load(): ScheduleConfig = runBlocking {
        val prefs = context.scheduleDataStore.data.first()
        ScheduleConfig(
            enabled = prefs[KEY_ENABLED] ?: false,
            recurrenceType = try {
                RecurrenceType.valueOf(prefs[KEY_RECURRENCE] ?: "MONTHLY")
            } catch (_: Exception) { RecurrenceType.MONTHLY },
            dayOfWeek = prefs[KEY_DAY_OF_WEEK] ?: 1,
            dayOfMonth = prefs[KEY_DAY_OF_MONTH] ?: 1,
            hour = prefs[KEY_HOUR] ?: 2,
            minute = prefs[KEY_MINUTE] ?: 0,
            deleteOlderThanMonths = prefs[KEY_DELETE_OLDER_THAN_MONTHS] ?: 24,
            includeSms = prefs[KEY_INCLUDE_SMS] ?: true,
            includeMmsMedia = prefs[KEY_INCLUDE_MMS_MEDIA] ?: true,
            includeMmsGroup = prefs[KEY_INCLUDE_MMS_GROUP] ?: true,
            includeRcs = prefs[KEY_INCLUDE_RCS] ?: false,
            batchSize = prefs[KEY_BATCH_SIZE] ?: 1000,
            batchSizeMmsMedia = prefs[KEY_BATCH_SIZE_MMS_MEDIA] ?: 100,
            batchSizeMmsGroup = prefs[KEY_BATCH_SIZE_MMS_GROUP] ?: 500,
            deleteChunkSize = prefs[KEY_DELETE_CHUNK_SIZE] ?: 50,
            delayMs = prefs[KEY_DELAY_MS] ?: 100,
            requiresCharging = prefs[KEY_REQUIRES_CHARGING] ?: true
        )
    }

    fun getLastRunMs(): Long = runBlocking {
        context.scheduleDataStore.data.first()[KEY_LAST_RUN_MS] ?: 0L
    }

    fun setLastRunMs(ms: Long) = runBlocking {
        context.scheduleDataStore.edit { it[KEY_LAST_RUN_MS] = ms }
    }
}
