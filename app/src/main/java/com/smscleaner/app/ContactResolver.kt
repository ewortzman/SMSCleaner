package com.smscleaner.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract

class ContactResolver(private val contentResolver: ContentResolver) {

    private val cache = LinkedHashMap<String, String>(100, 0.75f, true)

    fun resolve(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return phoneNumber

        cache[phoneNumber]?.let { return it }

        val name = lookupContact(phoneNumber) ?: phoneNumber
        if (cache.size >= MAX_CACHE_SIZE) {
            val oldest = cache.keys.first()
            cache.remove(oldest)
        }
        cache[phoneNumber] = name
        return name
    }

    private fun lookupContact(phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 500
    }
}
