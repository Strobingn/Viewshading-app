package com.viewshed.app.viewshed

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Phase 5 — Simple field forms (site name, weather, notes, conditions).
 */
class FieldDataForms(context: Context) {

    private val file = File(context.filesDir, "field_forms.json")
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<FieldForm>>() {}.type

    data class FieldForm(
        val id: String = UUID.randomUUID().toString(),
        val lat: Double,
        val lon: Double,
        val siteName: String,
        val weather: String,
        val conditions: String,
        val notes: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    fun list(): List<FieldForm> = load().sortedByDescending { it.createdAt }

    fun save(
        location: GeoPoint,
        siteName: String,
        weather: String,
        conditions: String,
        notes: String
    ): FieldForm {
        val form = FieldForm(
            lat = location.lat,
            lon = location.lon,
            siteName = siteName.ifBlank { "Site" },
            weather = weather,
            conditions = conditions,
            notes = notes
        )
        val all = load()
        all.add(form)
        write(all)
        return form
    }

    fun delete(id: String) {
        val all = load()
        all.removeAll { it.id == id }
        write(all)
    }

    private fun load(): MutableList<FieldForm> {
        if (!file.exists()) return mutableListOf()
        return try {
            gson.fromJson<MutableList<FieldForm>>(file.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun write(list: List<FieldForm>) {
        file.writeText(gson.toJson(list))
    }
}
