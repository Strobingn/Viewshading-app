package com.viewshed.app.viewshed

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Phase 5 — Searchable session history (last N analysis runs).
 */
class SessionHistory(context: Context) {

    private val file = File(context.filesDir, "session_history.json")
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<HistoryEntry>>() {}.type

    data class HistoryEntry(
        val id: String = UUID.randomUUID().toString(),
        val lat: Double,
        val lon: Double,
        val eyeHeightM: Double,
        val maxDistKm: Double,
        val areaKm2: Double,
        val maxRangeKm: Double,
        val label: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun list(): List<HistoryEntry> = load().sortedByDescending { it.timestamp }

    fun add(result: ViewshedResult, label: String = "Viewshed") {
        val e = HistoryEntry(
            lat = result.observer.lat,
            lon = result.observer.lon,
            eyeHeightM = result.params.eyeHeightM,
            maxDistKm = result.params.maxDistKm,
            areaKm2 = result.stats.areaKm2,
            maxRangeKm = result.stats.maxRangeKm,
            label = label
        )
        val all = load()
        all.add(0, e)
        while (all.size > MAX) all.removeAt(all.lastIndex)
        save(all)
    }

    fun search(query: String): List<HistoryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        return list().filter {
            it.label.lowercase().contains(q) ||
                "%.4f".format(it.lat).contains(q) ||
                "%.4f".format(it.lon).contains(q)
        }
    }

    fun clear() = save(mutableListOf())

    private fun load(): MutableList<HistoryEntry> {
        if (!file.exists()) return mutableListOf()
        return try {
            gson.fromJson<MutableList<HistoryEntry>>(file.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(list: List<HistoryEntry>) {
        file.writeText(gson.toJson(list))
    }

    companion object {
        private const val MAX = 50
    }
}
