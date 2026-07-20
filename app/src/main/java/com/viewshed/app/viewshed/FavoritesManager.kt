package com.viewshed.app.viewshed

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Favorite locations (JSON on disk — no DataStore dependency).
 */
class FavoritesManager(context: Context) {

    private val file = File(context.filesDir, "favorites.json")
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<Favorite>>() {}.type

    data class Favorite(
        val id: String = UUID.randomUUID().toString(),
        val lat: Double,
        val lon: Double,
        val name: String,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val location: GeoPoint get() = GeoPoint(lat, lon)
    }

    fun list(): List<Favorite> = load().sortedByDescending { it.createdAt }

    fun add(point: GeoPoint, name: String): Favorite {
        val fav = Favorite(lat = point.lat, lon = point.lon, name = name.ifBlank { "Favorite" })
        val all = load()
        all.removeAll {
            GeoMath.distanceM(it.location, point) < 15.0 && it.name == fav.name
        }
        all.add(fav)
        save(all)
        return fav
    }

    fun remove(id: String): Boolean {
        val all = load()
        val ok = all.removeAll { it.id == id }
        if (ok) save(all)
        return ok
    }

    private fun load(): MutableList<Favorite> {
        if (!file.exists()) return mutableListOf()
        return try {
            gson.fromJson<MutableList<Favorite>>(file.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(list: List<Favorite>) {
        file.writeText(gson.toJson(list))
    }
}
