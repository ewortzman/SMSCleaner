package com.smscleaner.app

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.Telephony
import kotlin.random.Random

class TestMessageGenerator(private val contentResolver: ContentResolver) {

    private val sampleBodies = listOf(
        "Hey, how are you?",
        "Can you pick up milk on the way home?",
        "Running late, be there in 10",
        "Did you see the game last night?",
        "Happy birthday!",
        "Meeting moved to 3pm",
        "Thanks for lunch!",
        "On my way",
        "Call me when you get a chance",
        "LOL that's hilarious",
        "See you tomorrow",
        "Good morning!",
        "Don't forget about dinner tonight",
        "Just got home",
        "Sounds good to me",
        "What time works for you?",
        "I'll be there in 5 minutes",
        "Can you send me the address?",
        "Got it, thanks!",
        "Let me check and get back to you"
    )

    fun generate(
        smsCount: Int,
        mmsMediaCount: Int,
        mmsGroupCount: Int,
        conversationCount: Int,
        startDateMs: Long,
        endDateMs: Long,
        onLog: (String) -> Unit
    ) {
        val phoneNumbers = (1..conversationCount).map { i ->
            "+1555%07d".format(1000 + i)
        }

        if (smsCount > 0) {
            onLog("Generating $smsCount SMS messages across $conversationCount conversations...")
            generateSmsMessages(smsCount, phoneNumbers, startDateMs, endDateMs, onLog)
        }

        if (mmsMediaCount > 0) {
            onLog("Generating $mmsMediaCount MMS media messages...")
            generateMmsMessages(mmsMediaCount, phoneNumbers, startDateMs, endDateMs, hasMedia = true, isGroup = false, onLog)
        }

        if (mmsGroupCount > 0) {
            onLog("Generating $mmsGroupCount MMS group messages...")
            generateMmsMessages(mmsGroupCount, phoneNumbers, startDateMs, endDateMs, hasMedia = false, isGroup = true, onLog)
        }
    }

    private fun generateSmsMessages(
        count: Int,
        phoneNumbers: List<String>,
        startDateMs: Long,
        endDateMs: Long,
        onLog: (String) -> Unit
    ) {
        var inserted = 0
        for (i in 0 until count) {
            val phone = phoneNumbers[i % phoneNumbers.size]
            val timestamp = randomTimestamp(startDateMs, endDateMs)
            val body = sampleBodies[Random.nextInt(sampleBodies.size)]
            val isIncoming = Random.nextBoolean()

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, phone)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, if (isIncoming) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT)
            }

            try {
                contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                inserted++
            } catch (e: Exception) {
                onLog("Failed to insert SMS #$i: ${e.message}")
            }

            if (inserted % 100 == 0 && inserted > 0) {
                onLog("  SMS progress: $inserted / $count")
            }
        }
        onLog("SMS generation complete: $inserted / $count inserted")
    }

    private fun generateMmsMessages(
        count: Int,
        phoneNumbers: List<String>,
        startDateMs: Long,
        endDateMs: Long,
        hasMedia: Boolean,
        isGroup: Boolean,
        onLog: (String) -> Unit
    ) {
        var inserted = 0
        for (i in 0 until count) {
            val timestamp = randomTimestamp(startDateMs, endDateMs)
            val timestampSec = timestamp / 1000

            val mmsValues = ContentValues().apply {
                put(Telephony.Mms.DATE, timestampSec)
                put(Telephony.Mms.DATE_SENT, timestampSec)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
                put(Telephony.Mms.MESSAGE_TYPE, 132) // MESSAGE_TYPE_RETRIEVE_CONF
            }

            try {
                val mmsUri = contentResolver.insert(Telephony.Mms.CONTENT_URI, mmsValues) ?: continue
                val mmsId = mmsUri.lastPathSegment ?: continue

                if (isGroup) {
                    val groupSize = Random.nextInt(2, minOf(5, phoneNumbers.size + 1))
                    val groupMembers = phoneNumbers.shuffled().take(groupSize)
                    for (member in groupMembers) {
                        insertMmsAddress(mmsId, member, 151) // PduHeaders.TO
                    }
                    insertMmsAddress(mmsId, "insert-address-token", 137) // PduHeaders.FROM
                } else {
                    val phone = phoneNumbers[i % phoneNumbers.size]
                    insertMmsAddress(mmsId, phone, 137) // FROM
                }

                val textPart = ContentValues().apply {
                    put(Telephony.Mms.Part.MSG_ID, mmsId)
                    put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
                    put(Telephony.Mms.Part.TEXT, sampleBodies[Random.nextInt(sampleBodies.size)])
                }
                contentResolver.insert(
                    Uri.parse("content://mms/$mmsId/part"),
                    textPart
                )

                if (hasMedia) {
                    val mediaPart = ContentValues().apply {
                        put(Telephony.Mms.Part.MSG_ID, mmsId)
                        put(Telephony.Mms.Part.CONTENT_TYPE, "image/jpeg")
                        put(Telephony.Mms.Part.NAME, "test_image_$i.jpg")
                        put(Telephony.Mms.Part.TEXT, "")
                    }
                    contentResolver.insert(
                        Uri.parse("content://mms/$mmsId/part"),
                        mediaPart
                    )
                }

                inserted++
            } catch (e: Exception) {
                onLog("Failed to insert MMS #$i: ${e.message}")
            }

            if (inserted % 50 == 0 && inserted > 0) {
                val type = if (hasMedia) "MMS media" else "MMS group"
                onLog("  $type progress: $inserted / $count")
            }
        }

        val type = if (hasMedia) "MMS media" else "MMS group"
        onLog("$type generation complete: $inserted / $count inserted")
    }

    private fun insertMmsAddress(mmsId: String, address: String, type: Int) {
        val addrValues = ContentValues().apply {
            put("address", address)
            put("type", type)
            put("charset", 106) // UTF-8
        }
        contentResolver.insert(
            Uri.parse("content://mms/$mmsId/addr"),
            addrValues
        )
    }

    private fun randomTimestamp(startMs: Long, endMs: Long): Long {
        return startMs + Random.nextLong(endMs - startMs + 1)
    }
}
