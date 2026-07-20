package com.viewshed.app.viewshed

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Phase 2 — Persistent field notes tied to map locations.
 */
class FieldNotesManager(context: Context) {

    private val file = File(context.filesDir, "field_notes.json")
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<FieldNote>>() {}.type

    data class FieldNote(
        val id: String = UUID.randomUUID().toString(),
        val lat: Double,
        val lon: Double,
        val text: String,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val location: GeoPoint get() = GeoPoint(lat, lon)
    }

    fun list(): List<FieldNote> = load().sortedByDescending { it.createdAt }

    fun add(location: GeoPoint, text: String): FieldNote {
        val note = FieldNote(
            lat = location.lat,
            lon = location.lon,
            text = text.trim()
        )
        val all = load()
        all.add(note)
        save(all)
        return note
    }

    fun update(id: String, text: String): FieldNote? {
        val all = load()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = all[idx].copy(text = text.trim())
        all[idx] = updated
        save(all)
        return updated
    }

    fun remove(id: String): Boolean {
        val all = load()
        val changed = all.removeAll { it.id == id }
        if (changed) save(all)
        return changed
    }

    fun clear() {
        save(mutableListOf())
    }

    fun get(id: String): FieldNote? = load().find { it.id == id }

    fun nearest(location: GeoPoint, maxM: Double = 50.0): FieldNote? {
        return list()
            .map { it to GeoMath.distanceM(location, it.location) }
            .filter { it.second <= maxM }
            .minByOrNull { it.second }
            ?.first
    }

    private fun load(): MutableList<FieldNote> {
        if (!file.exists()) return mutableListOf()
        return try {
            gson.fromJson<MutableList<FieldNote>>(file.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(notes: List<FieldNote>) {
        file.writeText(gson.toJson(notes))
    }
}
