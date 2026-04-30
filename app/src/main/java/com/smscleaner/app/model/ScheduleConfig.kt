package com.smscleaner.app.model

import org.json.JSONObject
import java.util.UUID

enum class RecurrenceType { WEEKLY, BIWEEKLY, MONTHLY }

data class ScheduleConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Cleanup",
    val enabled: Boolean = false,
    val recurrenceType: RecurrenceType = RecurrenceType.MONTHLY,
    val dayOfWeek: Int = 1,
    val dayOfMonth: Int = 1,
    val hour: Int = 2,
    val minute: Int = 0,
    val deleteOlderThanMonths: Int = 24,
    val includeSms: Boolean = true,
    val includeMmsMedia: Boolean = true,
    val includeMmsGroup: Boolean = true,
    val includeRcs: Boolean = false,
    val batchSize: Int = 1000,
    val batchSizeMmsMedia: Int = 100,
    val batchSizeMmsGroup: Int = 500,
    val deleteChunkSize: Int = 50,
    val delayMs: Long = 100,
    val requiresCharging: Boolean = true,
    val lastRunMs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("recurrenceType", recurrenceType.name)
        put("dayOfWeek", dayOfWeek)
        put("dayOfMonth", dayOfMonth)
        put("hour", hour)
        put("minute", minute)
        put("deleteOlderThanMonths", deleteOlderThanMonths)
        put("includeSms", includeSms)
        put("includeMmsMedia", includeMmsMedia)
        put("includeMmsGroup", includeMmsGroup)
        put("includeRcs", includeRcs)
        put("batchSize", batchSize)
        put("batchSizeMmsMedia", batchSizeMmsMedia)
        put("batchSizeMmsGroup", batchSizeMmsGroup)
        put("deleteChunkSize", deleteChunkSize)
        put("delayMs", delayMs)
        put("requiresCharging", requiresCharging)
        put("lastRunMs", lastRunMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScheduleConfig {
            return ScheduleConfig(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "Cleanup"),
                enabled = obj.optBoolean("enabled", false),
                recurrenceType = try {
                    RecurrenceType.valueOf(obj.optString("recurrenceType", "MONTHLY"))
                } catch (_: Exception) { RecurrenceType.MONTHLY },
                dayOfWeek = obj.optInt("dayOfWeek", 1),
                dayOfMonth = obj.optInt("dayOfMonth", 1),
                hour = obj.optInt("hour", 2),
                minute = obj.optInt("minute", 0),
                deleteOlderThanMonths = obj.optInt("deleteOlderThanMonths", 24),
                includeSms = obj.optBoolean("includeSms", true),
                includeMmsMedia = obj.optBoolean("includeMmsMedia", true),
                includeMmsGroup = obj.optBoolean("includeMmsGroup", true),
                includeRcs = obj.optBoolean("includeRcs", false),
                batchSize = obj.optInt("batchSize", 1000),
                batchSizeMmsMedia = obj.optInt("batchSizeMmsMedia", 100),
                batchSizeMmsGroup = obj.optInt("batchSizeMmsGroup", 500),
                deleteChunkSize = obj.optInt("deleteChunkSize", 50),
                delayMs = obj.optLong("delayMs", 100),
                requiresCharging = obj.optBoolean("requiresCharging", true),
                lastRunMs = obj.optLong("lastRunMs", 0L)
            )
        }
    }
}
