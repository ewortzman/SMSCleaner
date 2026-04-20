package com.smscleaner.app

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.coroutineContext

enum class DeleteOrder { OLDEST_FIRST, NEWEST_FIRST }

data class CleanerConfig(
    val startDateMs: Long?,
    val endDateMs: Long?,
    val includeSms: Boolean,
    val includeMmsMedia: Boolean,
    val includeMmsGroup: Boolean,
    val includeRcs: Boolean,
    val batchSize: Int,
    val delayMs: Long,
    val dryRun: Boolean,
    val deleteOrder: DeleteOrder = DeleteOrder.OLDEST_FIRST
)

data class ConversationSummary(
    val threadId: String,
    val address: String,
    val displayName: String,
    val count: Int
)

class MessageCleaner(
    private val contentResolver: ContentResolver,
    private val contactResolver: ContactResolver,
    private val config: CleanerConfig,
    private val onLog: (String) -> Unit
) {

    private var totalFound = 0
    private var totalProcessed = 0
    private var totalSizeBytes = 0L
    private val sortDirection = if (config.deleteOrder == DeleteOrder.OLDEST_FIRST) "ASC" else "DESC"
    private val dateFmt = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    suspend fun execute(): Int {
        totalFound = 0
        totalProcessed = 0
        totalSizeBytes = 0L

        if (config.includeSms) {
            processSmsMessages()
        }

        if (config.includeMmsMedia || config.includeMmsGroup) {
            processMmsMessages()
        }

        if (config.includeRcs) {
            processRcsMessages()
        }

        val action = if (config.dryRun) "found" else "deleted"
        onLog("--- Complete: $totalProcessed messages $action (~${formatSize(totalSizeBytes)}) ---")
        return totalProcessed
    }

    private suspend fun processSmsMessages() {
        onLog("=== Scanning SMS messages ===")
        val uri = Telephony.Sms.CONTENT_URI
        val selection = buildDateSelection("date")
        val selectionArgs = buildDateSelectionArgs(useSeconds = false)

        val conversationMap = mutableMapOf<String, MutableList<Long>>()
        val addressMap = mutableMapOf<String, String>()
        var smsBytes = 0L

        var offset = 0
        var hasMore = true

        while (hasMore) {
            coroutineContext.ensureActive()

            val ids = mutableListOf<Long>()
            var batchMinDate = Long.MAX_VALUE
            var batchMaxDate = Long.MIN_VALUE
            var batchBytes = 0L
            contentResolver.query(
                uri,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, "date", Telephony.Sms.BODY),
                selection,
                selectionArgs,
                "date $sortDirection LIMIT ${config.batchSize} OFFSET $offset"
            )?.use { cursor ->
                if (cursor.count == 0) {
                    hasMore = false
                    return@use
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val threadId = cursor.getString(1) ?: "unknown"
                    val address = cursor.getString(2) ?: "unknown"
                    val date = cursor.getLong(3)
                    val body = cursor.getString(4)
                    ids.add(id)
                    if (date < batchMinDate) batchMinDate = date
                    if (date > batchMaxDate) batchMaxDate = date
                    // ~row overhead (address, timestamps, indexes) + body bytes
                    batchBytes += ROW_OVERHEAD + (body?.toByteArray()?.size ?: 0)
                    conversationMap.getOrPut(threadId) { mutableListOf() }.add(id)
                    addressMap[threadId] = address
                }
                if (cursor.count < config.batchSize) hasMore = false
            } ?: run { hasMore = false }

            totalFound += ids.size
            smsBytes += batchBytes
            offset += ids.size

            val dateRange = formatDateRange(batchMinDate, batchMaxDate)
            if (ids.isNotEmpty() && !config.dryRun) {
                deleteBatch(uri, ids)
                totalProcessed += ids.size
                onLog("SMS batch: deleted ${ids.size} messages ($dateRange) [total: $totalProcessed]")
                offset = 0
            } else if (ids.isNotEmpty()) {
                totalProcessed += ids.size
                onLog("SMS batch: found ${ids.size} messages ($dateRange) [total: $totalProcessed]")
            }

            if (hasMore && ids.isNotEmpty()) {
                delay(config.delayMs)
            }
        }

        totalSizeBytes += smsBytes
        val action = if (config.dryRun) "found" else "deleted"
        onLog("SMS total: ${conversationMap.values.sumOf { it.size }} messages, ~${formatSize(smsBytes)} estimated")
        logConversationSummary("SMS", conversationMap, addressMap)
    }

    private suspend fun processMmsMessages() {
        onLog("=== Scanning MMS messages ===")
        val uri = Telephony.Mms.CONTENT_URI
        val selection = buildDateSelection("date")
        val selectionArgs = buildDateSelectionArgs(useSeconds = true)

        val mediaConversations = mutableMapOf<String, MutableList<Long>>()
        val groupConversations = mutableMapOf<String, MutableList<Long>>()
        val addressMap = mutableMapOf<String, String>()
        val idsToDelete = mutableListOf<Long>()
        val datesToDelete = mutableListOf<Long>()
        var mediaBytes = 0L
        var groupBytes = 0L

        var offset = 0
        var hasMore = true

        while (hasMore) {
            coroutineContext.ensureActive()

            data class MmsRow(val id: Long, val threadId: String, val dateSec: Long)
            val batchRows = mutableListOf<MmsRow>()
            contentResolver.query(
                uri,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, "date"),
                selection,
                selectionArgs,
                "date $sortDirection LIMIT ${config.batchSize} OFFSET $offset"
            )?.use { cursor ->
                if (cursor.count == 0) {
                    hasMore = false
                    return@use
                }
                while (cursor.moveToNext()) {
                    batchRows.add(MmsRow(cursor.getLong(0), cursor.getString(1) ?: "unknown", cursor.getLong(2)))
                }
                if (cursor.count < config.batchSize) hasMore = false
            } ?: run { hasMore = false }

            for (row in batchRows) {
                coroutineContext.ensureActive()

                val hasMedia = mmsHasMedia(row.id)
                val isGroup = mmsIsGroup(row.id)
                val address = getMmsAddress(row.id) ?: "unknown"
                addressMap[row.threadId] = address

                val isMediaMatch = hasMedia && config.includeMmsMedia
                val isGroupMatch = isGroup && !hasMedia && config.includeMmsGroup
                val shouldInclude = isMediaMatch || isGroupMatch

                if (shouldInclude) {
                    idsToDelete.add(row.id)
                    datesToDelete.add(row.dateSec * 1000)
                    val partSize = getMmsPartSize(row.id)
                    if (isMediaMatch) {
                        mediaBytes += partSize
                        mediaConversations.getOrPut(row.threadId) { mutableListOf() }.add(row.id)
                    } else {
                        groupBytes += partSize
                        groupConversations.getOrPut(row.threadId) { mutableListOf() }.add(row.id)
                    }
                }
            }

            offset += batchRows.size

            if (idsToDelete.size >= config.batchSize) {
                val batch = idsToDelete.toList()
                val batchDates = datesToDelete.toList()
                idsToDelete.clear()
                datesToDelete.clear()
                totalFound += batch.size
                val dateRange = formatDateRange(batchDates.min(), batchDates.max())

                if (!config.dryRun) {
                    deleteBatch(uri, batch)
                    totalProcessed += batch.size
                    onLog("MMS batch: deleted ${batch.size} messages ($dateRange) [total: $totalProcessed]")
                    offset = 0
                } else {
                    totalProcessed += batch.size
                    onLog("MMS batch: found ${batch.size} messages ($dateRange) [total: $totalProcessed]")
                }
                delay(config.delayMs)
            }
        }

        if (idsToDelete.isNotEmpty()) {
            totalFound += idsToDelete.size
            val dateRange = formatDateRange(datesToDelete.min(), datesToDelete.max())
            if (!config.dryRun) {
                deleteBatch(Telephony.Mms.CONTENT_URI, idsToDelete)
                totalProcessed += idsToDelete.size
                onLog("MMS final batch: deleted ${idsToDelete.size} messages ($dateRange) [total: $totalProcessed]")
            } else {
                totalProcessed += idsToDelete.size
                onLog("MMS final batch: found ${idsToDelete.size} messages ($dateRange) [total: $totalProcessed]")
            }
        }

        if (config.includeMmsMedia) {
            totalSizeBytes += mediaBytes
            onLog("MMS (media) total: ${mediaConversations.values.sumOf { it.size }} messages, ~${formatSize(mediaBytes)}")
            logConversationSummary("MMS (media)", mediaConversations, addressMap)
        }
        if (config.includeMmsGroup) {
            totalSizeBytes += groupBytes
            onLog("MMS (group) total: ${groupConversations.values.sumOf { it.size }} messages, ~${formatSize(groupBytes)}")
            logConversationSummary("MMS (group)", groupConversations, addressMap)
        }
    }

    private suspend fun processRcsMessages() {
        onLog("=== Scanning RCS messages ===")
        try {
            val uri = Uri.parse("content://im/chat")
            contentResolver.query(uri, null, null, null, null)?.use {
                onLog("RCS provider found, but direct RCS deletion is carrier-dependent.")
                onLog("RCS messages may appear in SMS table on some devices.")
            }
        } catch (_: Exception) {
            onLog("RCS provider not available on this device.")
            onLog("Some RCS messages may be stored in SMS/MMS tables and handled by those scans.")
        }
    }

    private fun mmsHasMedia(mmsId: Long): Boolean {
        val partUri = Uri.parse("content://mms/$mmsId/part")
        return try {
            contentResolver.query(
                partUri,
                arrayOf("ct"),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val contentType = cursor.getString(0) ?: continue
                    if (contentType.startsWith("image/") ||
                        contentType.startsWith("video/") ||
                        contentType.startsWith("audio/")
                    ) {
                        return@use true
                    }
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun mmsIsGroup(mmsId: Long): Boolean {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        return try {
            contentResolver.query(
                addrUri,
                arrayOf("address", "type"),
                null, null, null
            )?.use { cursor ->
                val recipients = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(0) ?: continue
                    val type = cursor.getInt(1)
                    // 137 = FROM (sender), skip it. Count TO (151) and other recipient types.
                    if (type == 137) continue
                    if (addr.isNotBlank() && addr != "insert-address-token") {
                        recipients.add(addr)
                    }
                }
                recipients.size > 1
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun getMmsPartSize(mmsId: Long): Long {
        val partUri = Uri.parse("content://mms/$mmsId/part")
        var size = 0L
        try {
            contentResolver.query(
                partUri,
                arrayOf("_id", "_data", "ct", "text"),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(0)
                    val data = cursor.getString(1)
                    val ct = cursor.getString(2) ?: ""
                    val text = cursor.getString(3)
                    if (data != null && (ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/"))) {
                        // Try to get actual file size via content provider
                        try {
                            val pUri = Uri.parse("content://mms/part/$partId")
                            contentResolver.openAssetFileDescriptor(pUri, "r")?.use { afd ->
                                size += afd.length.let { if (it >= 0) it else 0L }
                            }
                        } catch (_: Exception) { }
                    } else {
                        size += text?.toByteArray()?.size?.toLong() ?: 0L
                    }
                }
            }
        } catch (_: Exception) { }
        return size + ROW_OVERHEAD
    }

    private fun getMmsAddress(mmsId: Long): String? {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        return try {
            contentResolver.query(
                addrUri,
                arrayOf("address"),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteBatch(uri: Uri, ids: List<Long>) {
        val isMms = uri == Telephony.Mms.CONTENT_URI
        if (isMms) {
            for (id in ids) {
                // Delete parts (and their backing media files) before deleting the MMS record
                try {
                    contentResolver.delete(Uri.parse("content://mms/$id/part"), null, null)
                } catch (_: Exception) { }
            }
        }
        val idList = ids.joinToString(",")
        contentResolver.delete(uri, "_id IN ($idList)", null)
    }

    private fun formatDateRange(minMs: Long, maxMs: Long): String {
        val from = dateFmt.format(Date(minMs))
        val to = dateFmt.format(Date(maxMs))
        return if (from == to) from else "$from \u2013 $to"
    }

    private fun buildDateSelection(dateColumn: String): String? {
        return when {
            config.startDateMs != null && config.endDateMs != null ->
                "$dateColumn >= ? AND $dateColumn <= ?"
            config.startDateMs != null ->
                "$dateColumn >= ?"
            config.endDateMs != null ->
                "$dateColumn <= ?"
            else -> null
        }
    }

    private fun buildDateSelectionArgs(useSeconds: Boolean): Array<String>? {
        val divisor = if (useSeconds) 1000L else 1L
        val args = mutableListOf<String>()

        config.startDateMs?.let { args.add((it / divisor).toString()) }
        config.endDateMs?.let { args.add((it / divisor).toString()) }

        return if (args.isEmpty()) null else args.toTypedArray()
    }

    private fun logConversationSummary(
        type: String,
        conversationMap: Map<String, List<Long>>,
        addressMap: Map<String, String>
    ) {
        if (conversationMap.isEmpty()) {
            onLog("$type: no messages found in date range")
            return
        }

        onLog("--- $type Summary ---")
        conversationMap.entries
            .sortedByDescending { it.value.size }
            .forEach { (threadId, ids) ->
                val address = addressMap[threadId] ?: "unknown"
                val displayName = contactResolver.resolve(address)
                val label = if (displayName != address) "$displayName ($address)" else address
                onLog("  Thread $threadId | $label | ${ids.size} messages")
            }
        onLog("$type total: ${conversationMap.values.sumOf { it.size }} messages")
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    companion object {
        private const val ROW_OVERHEAD = 200L
    }
}
