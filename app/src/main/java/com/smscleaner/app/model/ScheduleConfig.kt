package com.smscleaner.app.model

enum class RecurrenceType { WEEKLY, BIWEEKLY, MONTHLY }

data class ScheduleConfig(
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
    val delayMs: Long = 100
)
