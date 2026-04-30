package com.smscleaner.app.schedule

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smscleaner.app.model.ScheduleConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private val Context.scheduleDataStore by preferencesDataStore(name = "schedule_prefs")

class SchedulePreferences(private val context: Context) {

    private val KEY_SCHEDULES = stringPreferencesKey("schedules_json")

    fun loadAll(): List<ScheduleConfig> = runBlocking {
        val raw = context.scheduleDataStore.data.first()[KEY_SCHEDULES] ?: return@runBlocking emptyList()
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ScheduleConfig.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun loadById(id: String): ScheduleConfig? = loadAll().firstOrNull { it.id == id }

    fun saveAll(schedules: List<ScheduleConfig>) = runBlocking {
        val arr = JSONArray()
        for (s in schedules) arr.put(s.toJson())
        context.scheduleDataStore.edit { it[KEY_SCHEDULES] = arr.toString() }
    }

    fun upsert(config: ScheduleConfig) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == config.id }
        if (idx >= 0) all[idx] = config else all.add(config)
        saveAll(all)
    }

    fun remove(id: String) {
        saveAll(loadAll().filter { it.id != id })
    }

    fun updateLastRun(id: String, ms: Long) {
        val existing = loadById(id) ?: return
        upsert(existing.copy(lastRunMs = ms))
    }
}
