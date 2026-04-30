package com.smscleaner.app.schedule

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.exclusionDataStore by preferencesDataStore(name = "exclusion_prefs")

class ExclusionPreferences(private val context: Context) {

    private val KEY_EXCLUDED_NUMBERS = stringSetPreferencesKey("excluded_numbers")

    fun load(): Set<String> = runBlocking {
        context.exclusionDataStore.data.first()[KEY_EXCLUDED_NUMBERS] ?: emptySet()
    }

    fun save(numbers: Set<String>) = runBlocking {
        context.exclusionDataStore.edit { it[KEY_EXCLUDED_NUMBERS] = numbers }
    }

    fun add(number: String) {
        save(load() + normalize(number))
    }

    fun remove(number: String) {
        save(load() - normalize(number))
    }

    companion object {
        fun normalize(number: String): String = number.filter { it.isDigit() || it == '+' }
    }
}
