package com.smscleaner.app

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class CleanerConfig(
    val startDateMs: Long?,
    val endDateMs: Long?,
    val includeSms: Boolean,
    val includeMmsMedia: Boolean,
    val includeMmsGroup: Boolean,
    val includeRcs: Boolean,
    val batchSize: Int,
    val delayMs: Long,
    val dryRun: Boolean
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

    suspend fun execute(): Int {
        totalFound = 0
        totalProcessed = 0

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
        onLog("--- Complete: $totalProcessed messages $action ---")
        return totalProcessed
    }

    private suspend fun processSmsMessages() {
        onLog("=== Scanning SMS messages ===")
        val uri = Telephony.Sms.CONTENT_URI
        val selection = buildDateSelection("date")
        val selectionArgs = buildDateSelectionArgs(useSeconds = false)

        val conversationMap = mutableMapOf<String, MutableList<Long>>()
        val addressMap = mutableMapOf<String, String>()

        var offset = 0
        var hasMore = true

        while (hasMore) {
            coroutineContext.ensureActive()

            val ids = mutableListOf<Long>()
            contentResolver.query(
                uri,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS),
                selection,
                selectionArgs,
                "${Telephony.Sms._ID} ASC LIMIT ${config.batchSize} OFFSET $offset"
            )?.use { cursor ->
                if (cursor.count == 0) {
                    hasMore = false
                    return@use
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val threadId = cursor.getString(1) ?: "unknown"
                    val address = cursor.getString(2) ?: "unknown"
                    ids.add(id)
                    conversationMap.getOrPut(threadId) { mutableListOf() }.add(id)
                    addressMap[threadId] = address
                }
                if (cursor.count < config.batchSize) hasMore = false
            } ?: run { hasMore = false }

            totalFound += ids.size
            offset += ids.size

            if (ids.isNotEmpty() && !config.dryRun) {
                deleteBatch(uri, ids)
                totalProcessed += ids.size
                onLog("SMS batch: deleted ${ids.size} messages (total: $totalProcessed)")
                offset = 0
            } else if (ids.isNotEmpty()) {
                totalProcessed += ids.size
                onLog("SMS batch: found ${ids.size} messages (total: $totalProcessed)")
            }

            if (hasMore && ids.isNotEmpty()) {
                delay(config.delayMs)
            }
        }

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

        var offset = 0
        var hasMore = true

        while (hasMore) {
            coroutineContext.ensureActive()

            val batchIds = mutableListOf<Pair<Long, String>>()
            contentResolver.query(
                uri,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
                selection,
                selectionArgs,
                "${Telephony.Mms._ID} ASC LIMIT ${config.batchSize} OFFSET $offset"
            )?.use { cursor ->
                if (cursor.count == 0) {
                    hasMore = false
                    return@use
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val threadId = cursor.getString(1) ?: "unknown"
                    batchIds.add(id to threadId)
                }
                if (cursor.count < config.batchSize) hasMore = false
            } ?: run { hasMore = false }

            for ((id, threadId) in batchIds) {
                coroutineContext.ensureActive()

                val hasMedia = mmsHasMedia(id)
                val isGroup = mmsIsGroup(id)
                val address = getMmsAddress(id) ?: "unknown"
                addressMap[threadId] = address

                val shouldInclude = (config.includeMmsMedia && hasMedia) ||
                        (config.includeMmsGroup && isGroup && !hasMedia)

                if (shouldInclude) {
                    idsToDelete.add(id)
                    if (hasMedia) {
                        mediaConversations.getOrPut(threadId) { mutableListOf() }.add(id)
                    } else {
                        groupConversations.getOrPut(threadId) { mutableListOf() }.add(id)
                    }
                }
            }

            offset += batchIds.size

            if (idsToDelete.size >= config.batchSize) {
                val batch = idsToDelete.toList()
                idsToDelete.clear()
                totalFound += batch.size

                if (!config.dryRun) {
                    deleteBatch(uri, batch)
                    totalProcessed += batch.size
                    onLog("MMS batch: deleted ${batch.size} messages (total: $totalProcessed)")
                    offset = 0
                } else {
                    totalProcessed += batch.size
                    onLog("MMS batch: found ${batch.size} messages (total: $totalProcessed)")
                }
                delay(config.delayMs)
            }
        }

        if (idsToDelete.isNotEmpty()) {
            totalFound += idsToDelete.size
            if (!config.dryRun) {
                deleteBatch(Telephony.Mms.CONTENT_URI, idsToDelete)
                totalProcessed += idsToDelete.size
                onLog("MMS final batch: deleted ${idsToDelete.size} messages (total: $totalProcessed)")
            } else {
                totalProcessed += idsToDelete.size
                onLog("MMS final batch: found ${idsToDelete.size} messages (total: $totalProcessed)")
            }
        }

        if (config.includeMmsMedia) {
            logConversationSummary("MMS (media)", mediaConversations, addressMap)
        }
        if (config.includeMmsGroup) {
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
                val addresses = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(0) ?: continue
                    val type = cursor.getInt(1)
                    if (type == 151) continue // 151 = from, skip sender
                    addresses.add(addr)
                }
                addresses.size > 1
            } ?: false
        } catch (_: Exception) {
            false
        }
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
        val idList = ids.joinToString(",")
        contentResolver.delete(uri, "_id IN ($idList)", null)
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
}
