package com.smscleaner.app

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony
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
    val batchSizeMmsMedia: Int = batchSize,
    val batchSizeMmsGroup: Int = batchSize,
    val deleteChunkSize: Int = 50,
    val delayMs: Long,
    val dryRun: Boolean,
    val deleteOrder: DeleteOrder = DeleteOrder.OLDEST_FIRST,
    val debugLogging: Boolean = false
)

data class ScanResults(
    val smsThreadMap: Map<String, List<Long>>,
    val smsAddressMap: Map<String, String>,
    val mediaMmsIds: List<Long>,
    val mediaConversations: Map<String, List<Long>>,
    val groupMmsIds: List<Long>,
    val groupConversations: Map<String, List<Long>>,
    val mmsAddressMap: Map<String, String>
)

class MessageCleaner(
    private val contentResolver: ContentResolver,
    private val contactResolver: ContactResolver,
    private val config: CleanerConfig,
    private val onLog: (String) -> Unit
) {

    private var totalProcessed = 0
    private var totalSizeBytes = 0L
    private val sortDirection = if (config.deleteOrder == DeleteOrder.OLDEST_FIRST) "ASC" else "DESC"
    private val dateFmt = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val timestampFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var cachedScanResults: ScanResults? = null
    fun getScanResults(): ScanResults? = cachedScanResults

    private fun log(msg: String) {
        onLog("[${timestampFmt.format(Date())}] $msg")
    }

    private fun debugLog(msg: String) {
        if (config.debugLogging) {
            onLog("[${timestampFmt.format(Date())}] [DEBUG] $msg")
        }
    }

    suspend fun execute(previousScan: ScanResults? = null): Int {
        totalProcessed = 0
        totalSizeBytes = 0L

        logDatabaseTotals()

        try {
            if (config.dryRun) {
                val smsResult = if (config.includeSms) scanSms() else SmsScanResult()
                val mmsResult = if (config.includeMmsMedia || config.includeMmsGroup) scanMms() else MmsScanResult()
                if (config.includeRcs) processRcsMessages()

                cachedScanResults = ScanResults(
                    smsThreadMap = smsResult.threadMap.mapValues { it.value.toList() },
                    smsAddressMap = smsResult.addressMap.toMap(),
                    mediaMmsIds = mmsResult.mediaIds.toList(),
                    mediaConversations = mmsResult.mediaConversations.mapValues { it.value.toList() },
                    groupMmsIds = mmsResult.groupIds.toList(),
                    groupConversations = mmsResult.groupConversations.mapValues { it.value.toList() },
                    mmsAddressMap = mmsResult.addressMap.toMap()
                )
            } else if (previousScan != null) {
                log("Using cached scan results (skipping re-scan)")
                if (config.includeSms && previousScan.smsThreadMap.isNotEmpty()) {
                    deleteSmsFromScan(previousScan)
                }
                if (config.includeMmsMedia && previousScan.mediaMmsIds.isNotEmpty()) {
                    deleteMmsFromScan("MMS (media)", previousScan.mediaMmsIds,
                        previousScan.mediaConversations, previousScan.mmsAddressMap, config.batchSizeMmsMedia)
                }
                if (config.includeMmsGroup && previousScan.groupMmsIds.isNotEmpty()) {
                    deleteMmsFromScan("MMS (group)", previousScan.groupMmsIds,
                        previousScan.groupConversations, previousScan.mmsAddressMap, config.batchSizeMmsGroup)
                }
            } else {
                log("No cached scan — running full scan + delete")
                val smsResult = if (config.includeSms) scanSms() else SmsScanResult()
                if (config.includeSms && smsResult.threadMap.isNotEmpty()) {
                    val scan = ScanResults(
                        smsThreadMap = smsResult.threadMap.mapValues { it.value.toList() },
                        smsAddressMap = smsResult.addressMap.toMap(),
                        mediaMmsIds = emptyList(), mediaConversations = emptyMap(),
                        groupMmsIds = emptyList(), groupConversations = emptyMap(),
                        mmsAddressMap = emptyMap()
                    )
                    deleteSmsFromScan(scan)
                }
                val mmsResult = if (config.includeMmsMedia || config.includeMmsGroup) scanMms() else MmsScanResult()
                if (config.includeMmsMedia && mmsResult.mediaIds.isNotEmpty()) {
                    deleteMmsFromScan("MMS (media)", mmsResult.mediaIds,
                        mmsResult.mediaConversations.mapValues { it.value.toList() },
                        mmsResult.addressMap, config.batchSizeMmsMedia)
                }
                if (config.includeMmsGroup && mmsResult.groupIds.isNotEmpty()) {
                    deleteMmsFromScan("MMS (group)", mmsResult.groupIds,
                        mmsResult.groupConversations.mapValues { it.value.toList() },
                        mmsResult.addressMap, config.batchSizeMmsGroup)
                }
                if (config.includeRcs) processRcsMessages()
            }

            val action = if (config.dryRun) "found" else "deleted"
            log("--- Complete: $totalProcessed messages $action (~${formatSize(totalSizeBytes)}) ---")
        } catch (_: kotlinx.coroutines.CancellationException) {
            val action = if (config.dryRun) "found so far" else "deleted so far"
            log("--- Cancelled: $totalProcessed messages $action (~${formatSize(totalSizeBytes)}) ---")
            throw kotlinx.coroutines.CancellationException("cancelled")
        }
        return totalProcessed
    }

    // ──────────────────── SMS ────────────────────

    private data class SmsScanResult(
        val threadMap: MutableMap<String, MutableList<Long>> = mutableMapOf(),
        val addressMap: MutableMap<String, String> = mutableMapOf(),
        val sizeBytes: Long = 0L
    )

    private suspend fun scanSms(): SmsScanResult {
        log("=== Scanning SMS messages ===")
        val uri = Telephony.Sms.CONTENT_URI
        val selection = buildDateSelection("date")
        val selectionArgs = buildDateSelectionArgs(useSeconds = false)

        val result = SmsScanResult()
        var smsBytes = 0L
        var scanned = 0
        var offset = 0
        var hasMore = true

        while (hasMore) {
            coroutineContext.ensureActive()
            val queryStart = System.currentTimeMillis()
            var batchCount = 0
            contentResolver.query(
                uri,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                selection, selectionArgs,
                "date $sortDirection LIMIT ${config.batchSize} OFFSET $offset"
            )?.use { cursor ->
                if (cursor.count == 0) { hasMore = false; return@use }
                batchCount = cursor.count
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val threadId = cursor.getString(1) ?: "unknown"
                    val address = cursor.getString(2) ?: "unknown"
                    val body = cursor.getString(3)
                    smsBytes += ROW_OVERHEAD + (body?.toByteArray()?.size ?: 0)
                    result.threadMap.getOrPut(threadId) { mutableListOf() }.add(id)
                    result.addressMap[threadId] = address
                }
                if (cursor.count < config.batchSize) hasMore = false
            } ?: run { hasMore = false }

            offset += batchCount
            scanned += batchCount
            totalProcessed += batchCount
            debugLog("SMS scan: $batchCount rows in ${System.currentTimeMillis() - queryStart}ms [total: $scanned]")
            if (batchCount > 0) log("SMS scan: $scanned messages found so far")
            if (hasMore && batchCount > 0) delay(config.delayMs)
        }

        totalSizeBytes += smsBytes
        log("SMS total: $scanned messages, ~${formatSize(smsBytes)} estimated")
        logConversationSummary("SMS", result.threadMap, result.addressMap)
        return result
    }

    private suspend fun deleteSmsFromScan(scan: ScanResults) {
        val uri = Telephony.Sms.CONTENT_URI
        val totalToDelete = scan.smsThreadMap.values.sumOf { it.size }
        log("=== Deleting SMS messages ===")
        log("SMS: $totalToDelete messages across ${scan.smsThreadMap.size} threads")

        // Classify threads: whole vs partial using batch GROUP BY
        val wholeThreadIds = mutableListOf<String>()
        val partialThreads = mutableMapOf<String, List<Long>>()
        val countStart = System.currentTimeMillis()
        val threadCounts = getThreadCounts(uri, scan.smsThreadMap.keys)
        for ((threadId, ids) in scan.smsThreadMap) {
            val threadTotal = threadCounts[threadId] ?: 0
            if (threadTotal > 0 && threadTotal == ids.size) {
                wholeThreadIds.add(threadId)
            } else {
                partialThreads[threadId] = ids
            }
        }
        val wholeCount = wholeThreadIds.sumOf { scan.smsThreadMap[it]!!.size }
        val partialCount = partialThreads.values.sumOf { it.size }
        debugLog("Thread classification: ${wholeThreadIds.size} whole ($wholeCount msgs), ${partialThreads.size} partial ($partialCount msgs) in ${System.currentTimeMillis() - countStart}ms")

        var deletedSoFar = 0

        // Phase 1: whole-thread deletes — fast path
        if (wholeThreadIds.isNotEmpty()) {
            log("SMS Phase 1: deleting $wholeCount messages via ${wholeThreadIds.size} whole-thread deletes...")
            for (threadId in wholeThreadIds) {
                coroutineContext.ensureActive()
                val threadStart = System.currentTimeMillis()
                try {
                    val deleted = contentResolver.delete(
                        Uri.parse("content://sms/conversations/$threadId"), null, null
                    )
                    deletedSoFar += deleted
                    totalProcessed += deleted
                    debugLog("Thread $threadId: deleted $deleted in ${System.currentTimeMillis() - threadStart}ms")
                } catch (e: Exception) {
                    debugLog("Thread $threadId delete failed: ${e.message}")
                }
                log("  SMS: $deletedSoFar / $totalToDelete deleted")
            }
        }

        // Phase 2: partial-thread deletes — per-message, grouped by thread for sequential access
        if (partialThreads.isNotEmpty()) {
            log("SMS Phase 2: deleting $partialCount messages from ${partialThreads.size} partial threads...")
            val allPartialIds = partialThreads.entries.sortedBy { it.key }.flatMap { it.value }
            for (batch in allPartialIds.chunked(config.batchSize)) {
                coroutineContext.ensureActive()
                val batchStart = System.currentTimeMillis()
                val deleted = deleteWithProgress("SMS", uri, batch, deletedSoFar)
                deletedSoFar += deleted
                totalProcessed += deleted
                val batchElapsed = (System.currentTimeMillis() - batchStart) / 1000.0
                log("SMS: $deletedSoFar / $totalToDelete deleted [batch: %.1fs]".format(batchElapsed))
                delay(config.delayMs)
            }
        }

        log("SMS delete complete: $deletedSoFar deleted")
    }

    // ──────────────────── MMS ────────────────────

    private data class MmsScanResult(
        val mediaIds: MutableList<Long> = mutableListOf(),
        val mediaDates: MutableList<Long> = mutableListOf(),
        val mediaConversations: MutableMap<String, MutableList<Long>> = mutableMapOf(),
        val groupIds: MutableList<Long> = mutableListOf(),
        val groupDates: MutableList<Long> = mutableListOf(),
        val groupConversations: MutableMap<String, MutableList<Long>> = mutableMapOf(),
        val addressMap: MutableMap<String, String> = mutableMapOf(),
        var mediaBytes: Long = 0L,
        var groupBytes: Long = 0L
    )

    private suspend fun scanMms(): MmsScanResult {
        log("=== Scanning MMS messages ===")
        val uri = Telephony.Mms.CONTENT_URI
        val selection = buildDateSelection("date")
        val selectionArgs = buildDateSelectionArgs(useSeconds = true)
        val result = MmsScanResult()

        // Pre-fetch media MMS IDs and group thread IDs in bulk
        val mediaMmsIds: Set<Long> = if (config.includeMmsMedia) preloadMediaMmsIds(selection, selectionArgs) else emptySet()
        val groupThreadIds: Set<Long> = if (config.includeMmsGroup) preloadGroupThreadIds() else emptySet()

        val scanBatchSize = maxOf(config.batchSizeMmsMedia, config.batchSizeMmsGroup, config.batchSize)
        var offset = 0
        var hasMore = true
        var scanned = 0

        while (hasMore) {
            coroutineContext.ensureActive()
            val scanStart = System.currentTimeMillis()

            data class MmsRow(val id: Long, val threadId: Long, val dateSec: Long)
            val batchRows = mutableListOf<MmsRow>()
            contentResolver.query(
                uri,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, "date"),
                selection, selectionArgs,
                "date $sortDirection LIMIT $scanBatchSize OFFSET $offset"
            )?.use { cursor ->
                if (cursor.count == 0) { hasMore = false; return@use }
                while (cursor.moveToNext()) {
                    batchRows.add(MmsRow(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2)))
                }
                if (cursor.count < scanBatchSize) hasMore = false
            } ?: run { hasMore = false }
            debugLog("MMS scan query: ${batchRows.size} rows in ${System.currentTimeMillis() - scanStart}ms")

            var matchCount = 0
            val classifyStart = System.currentTimeMillis()
            for (row in batchRows) {
                coroutineContext.ensureActive()
                val hasMedia = row.id in mediaMmsIds
                val isGroup = row.threadId in groupThreadIds
                val isMediaMatch = hasMedia && config.includeMmsMedia
                val isGroupMatch = isGroup && !hasMedia && config.includeMmsGroup

                if (isMediaMatch || isGroupMatch) {
                    matchCount++
                    // Only look up address/size during dry run — not needed for delete
                    if (config.dryRun) {
                        val address = getMmsAddress(row.id)
                        result.addressMap[row.threadId.toString()] = address
                    }

                    if (isMediaMatch) {
                        if (config.dryRun) result.mediaBytes += getMmsPartSize(row.id)
                        result.mediaIds.add(row.id)
                        result.mediaDates.add(row.dateSec * 1000)
                        result.mediaConversations.getOrPut(row.threadId.toString()) { mutableListOf() }.add(row.id)
                    } else {
                        if (config.dryRun) result.groupBytes += getMmsPartSize(row.id)
                        result.groupIds.add(row.id)
                        result.groupDates.add(row.dateSec * 1000)
                        result.groupConversations.getOrPut(row.threadId.toString()) { mutableListOf() }.add(row.id)
                    }
                }
            }
            debugLog("MMS classify: $matchCount matched of ${batchRows.size} in ${System.currentTimeMillis() - classifyStart}ms")

            offset += batchRows.size
            scanned += batchRows.size
            totalProcessed += matchCount
            if (batchRows.isNotEmpty()) {
                log("MMS scan: $scanned scanned, ${result.mediaIds.size} media + ${result.groupIds.size} group matched")
            }

            if (hasMore && batchRows.isNotEmpty()) delay(config.delayMs)
        }

        if (config.includeMmsMedia) {
            totalSizeBytes += result.mediaBytes
            log("MMS (media) total: ${result.mediaIds.size} messages, ~${formatSize(result.mediaBytes)}")
            logConversationSummary("MMS (media)", result.mediaConversations, result.addressMap)
        }
        if (config.includeMmsGroup) {
            totalSizeBytes += result.groupBytes
            log("MMS (group) total: ${result.groupIds.size} messages, ~${formatSize(result.groupBytes)}")
            logConversationSummary("MMS (group)", result.groupConversations, result.addressMap)
        }
        return result
    }

    private suspend fun deleteMmsFromScan(
        label: String,
        ids: List<Long>,
        conversations: Map<String, List<Long>>,
        addressMap: Map<String, String>,
        batchSize: Int
    ) {
        val uri = Telephony.Mms.CONTENT_URI
        val totalToDelete = ids.size
        log("=== Deleting $label messages ===")
        log("$label: $totalToDelete messages across ${conversations.size} threads")

        // Phase 1: whole-thread deletes — classify using batch GROUP BY
        val wholeThreadIds = mutableListOf<String>()
        val partialThreads = mutableMapOf<String, List<Long>>()
        val countStart = System.currentTimeMillis()
        val threadCounts = getThreadCounts(uri, conversations.keys)
        for ((threadId, threadIds) in conversations) {
            val threadTotal = threadCounts[threadId] ?: 0
            if (threadTotal > 0 && threadTotal == threadIds.size) {
                wholeThreadIds.add(threadId)
            } else {
                partialThreads[threadId] = threadIds
            }
        }
        val partialIds = partialThreads.entries.sortedBy { it.key }.flatMap { it.value }
        val wholeCount = wholeThreadIds.sumOf { conversations[it]!!.size }
        debugLog("$label thread classification: ${wholeThreadIds.size} whole ($wholeCount msgs), partial (${partialIds.size} msgs) in ${System.currentTimeMillis() - countStart}ms")

        var deletedSoFar = 0

        if (wholeThreadIds.isNotEmpty()) {
            log("$label Phase 1: deleting $wholeCount messages via ${wholeThreadIds.size} whole-thread deletes...")
            for (threadId in wholeThreadIds) {
                coroutineContext.ensureActive()
                val threadStart = System.currentTimeMillis()
                try {
                    val deleted = contentResolver.delete(uri, "thread_id = ?", arrayOf(threadId))
                    deletedSoFar += deleted
                    totalProcessed += deleted
                    debugLog("$label thread $threadId: deleted $deleted in ${System.currentTimeMillis() - threadStart}ms")
                } catch (e: Exception) {
                    debugLog("$label thread $threadId delete failed: ${e.message}")
                }
                log("  $label: $deletedSoFar / $totalToDelete deleted")
            }
        }

        // Phase 2: per-message deletes for partial threads
        if (partialIds.isNotEmpty()) {
            log("$label Phase 2: deleting ${partialIds.size} messages from partial threads...")
            for (batch in partialIds.chunked(batchSize)) {
                coroutineContext.ensureActive()
                val batchStart = System.currentTimeMillis()
                val deleted = deleteWithProgress(label, uri, batch, deletedSoFar)
                deletedSoFar += deleted
                totalProcessed += deleted
                val batchElapsed = (System.currentTimeMillis() - batchStart) / 1000.0
                log("$label: $deletedSoFar / $totalToDelete deleted [batch: %.1fs]".format(batchElapsed))
                delay(config.delayMs)
            }
        }

        log("$label delete complete: $deletedSoFar deleted")
    }

    // ──────────────────── MMS Preload ────────────────────

    private fun preloadMediaMmsIds(mmsDateSelection: String?, mmsDateArgs: Array<String>?): Set<Long> {
        val ids = mutableSetOf<Long>()
        val startTime = System.currentTimeMillis()
        try {
            val mmsIdsInRange = mutableSetOf<Long>()
            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID),
                mmsDateSelection, mmsDateArgs, null
            )?.use { cursor ->
                while (cursor.moveToNext()) { mmsIdsInRange.add(cursor.getLong(0)) }
            }
            debugLog("Found ${mmsIdsInRange.size} MMS in date range in ${System.currentTimeMillis() - startTime}ms")

            if (mmsIdsInRange.isEmpty()) return ids

            val partStart = System.currentTimeMillis()
            for (chunk in mmsIdsInRange.chunked(500)) {
                val idList = chunk.joinToString(",")
                contentResolver.query(
                    Uri.parse("content://mms/part"),
                    arrayOf("mid"),
                    "mid IN ($idList) AND (ct LIKE 'image/%' OR ct LIKE 'video/%' OR ct LIKE 'audio/%')",
                    null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) { ids.add(cursor.getLong(0)) }
                }
            }
            debugLog("Parts query found ${ids.size} media MMS in ${System.currentTimeMillis() - partStart}ms")
        } catch (e: Exception) {
            debugLog("preloadMediaMmsIds failed: ${e.message}")
        }
        return ids
    }

    private fun preloadGroupThreadIds(): Set<Long> {
        val groupIds = mutableSetOf<Long>()
        val startTime = System.currentTimeMillis()
        try {
            contentResolver.query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "recipient_ids"),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(0)
                    val recipientIds = cursor.getString(1) ?: ""
                    if (recipientIds.trim().contains(" ")) {
                        groupIds.add(threadId)
                    }
                }
            }
            debugLog("Found ${groupIds.size} group threads in ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            debugLog("preloadGroupThreadIds failed: ${e.message}")
        }
        return groupIds
    }

    // ──────────────────── Thread Helpers ────────────────────

    private fun getThreadCounts(uri: Uri, threadIds: Set<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        if (threadIds.isEmpty()) return counts
        try {
            for (chunk in threadIds.chunked(500)) {
                val idList = chunk.joinToString(",")
                contentResolver.query(
                    uri,
                    arrayOf("thread_id", "COUNT(*)"),
                    "thread_id IN ($idList) GROUP BY thread_id",
                    null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        counts[cursor.getString(0)] = cursor.getInt(1)
                    }
                }
            }
        } catch (e: Exception) {
            debugLog("Batch thread count failed: ${e.message}, falling back to per-thread")
            for (threadId in threadIds) {
                val count = countWithSelection(uri, "thread_id = ?", arrayOf(threadId))
                counts[threadId] = count.toInt()
            }
        }
        return counts
    }

    // ──────────────────── MMS Helpers ────────────────────

    private fun getMmsAddress(mmsId: Long): String {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        return try {
            contentResolver.query(addrUri, arrayOf("address"), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(0) ?: continue
                    if (addr.isNotBlank() && addr != "insert-address-token") return@use addr
                }
                "unknown"
            } ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    private fun getMmsPartSize(mmsId: Long): Long {
        val partUri = Uri.parse("content://mms/$mmsId/part")
        var size = 0L
        try {
            contentResolver.query(partUri, arrayOf("_id", "_data", "ct", "text"), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val ct = cursor.getString(cursor.getColumnIndexOrThrow("ct")) ?: ""
                    val isMedia = ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/")
                    if (isMedia) {
                        val data = cursor.getString(cursor.getColumnIndexOrThrow("_data"))
                        if (data != null) {
                            try {
                                val partId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                                contentResolver.openAssetFileDescriptor(
                                    Uri.parse("content://mms/part/$partId"), "r"
                                )?.use { afd -> size += afd.length.let { if (it >= 0) it else 0L } }
                            } catch (_: Exception) { }
                        }
                    } else {
                        val text = cursor.getString(cursor.getColumnIndexOrThrow("text"))
                        size += text?.toByteArray()?.size?.toLong() ?: 0L
                    }
                }
            }
        } catch (_: Exception) { }
        return size + ROW_OVERHEAD
    }

    // ──────────────────── Delete Engine ────────────────────

    private suspend fun deleteWithProgress(
        label: String,
        uri: Uri,
        ids: List<Long>,
        alreadyDeletedBefore: Int
    ): Int {
        val authority = uri.authority ?: return 0
        val chunks = ids.chunked(config.deleteChunkSize)
        var totalDeleted = 0
        for ((index, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val chunkStart = System.currentTimeMillis()
            val ops = ArrayList<ContentProviderOperation>(chunk.size)
            for (id in chunk) {
                ops.add(
                    ContentProviderOperation.newDelete(Uri.withAppendedPath(uri, id.toString())).build()
                )
            }
            try {
                val results = contentResolver.applyBatch(authority, ops)
                totalDeleted += results.size
            } catch (_: Exception) {
                for (id in chunk) {
                    try {
                        totalDeleted += contentResolver.delete(
                            Uri.withAppendedPath(uri, id.toString()), null, null
                        )
                    } catch (_: Exception) { }
                }
            }
            val chunkElapsed = System.currentTimeMillis() - chunkStart
            val running = alreadyDeletedBefore + totalDeleted
            if (config.debugLogging) {
                debugLog("$label chunk ${index + 1}/${chunks.size}: ${chunk.size} in ${chunkElapsed}ms [running: $running]")
            } else {
                log("  $label: $running deleted so far")
            }
        }
        return totalDeleted
    }

    // ──────────────────── RCS ────────────────────

    private suspend fun processRcsMessages() {
        log("=== Scanning RCS messages ===")
        try {
            val uri = Uri.parse("content://im/chat")
            contentResolver.query(uri, null, null, null, null)?.use {
                log("RCS provider found, but direct RCS deletion is carrier-dependent.")
            }
        } catch (_: Exception) {
            log("RCS provider not available on this device.")
        }
    }

    // ──────────────────── Utility ────────────────────

    private fun logDatabaseTotals() {
        val smsCount = countRows(Telephony.Sms.CONTENT_URI)
        val mmsCount = countRows(Telephony.Mms.CONTENT_URI)
        log("Database totals: $smsCount SMS, $mmsCount MMS (${smsCount + mmsCount} total)")
    }

    private fun countWithSelection(uri: Uri, selection: String?, selectionArgs: Array<String>?): Long {
        return try {
            contentResolver.query(uri, arrayOf("COUNT(*)"), selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun countRows(uri: Uri): Long = countWithSelection(uri, null, null)

    private fun formatDateRange(minMs: Long, maxMs: Long): String {
        val from = dateFmt.format(Date(minMs))
        val to = dateFmt.format(Date(maxMs))
        return if (from == to) from else "$from – $to"
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
        conversationMap: Map<String, out List<Long>>,
        addressMap: Map<String, String>
    ) {
        if (conversationMap.isEmpty()) {
            log("$type: no messages found in date range")
            return
        }
        log("--- $type Summary ---")
        conversationMap.entries
            .sortedByDescending { it.value.size }
            .forEach { (threadId, ids) ->
                val address = addressMap[threadId] ?: "unknown"
                val displayName = contactResolver.resolve(address)
                val label = if (displayName != address) "$displayName ($address)" else address
                log("  Thread $threadId | $label | ${ids.size} messages")
            }
        log("$type total: ${conversationMap.values.sumOf { it.size }} messages")
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
