package com.viewshed.app.viewshed

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore

import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("favorites")

object FavoritesManager {
    private val FAVORITES_KEY = stringSetPreferencesKey("favorite_locations")

    suspend fun addFavorite(context: Context, point: GeoPoint, name: String) {
        val prefs = context.dataStore.data.first()
        val current = prefs[FAVORITES_KEY]?.toMutableSet() ?: mutableSetOf()
        current.add("${point.latitude},${point.longitude}|$name")
        context.dataStore.edit { it[FAVORITES_KEY] = current }
    }

    suspend fun getFavorites(context: Context): List<Pair<GeoPoint, String>> {
        val prefs = context.dataStore.data.first()
        return prefs[FAVORITES_KEY]?.map {
            val parts = it.split("|")
            val coords = parts[0].split(",").map { it.toDouble() }
            GeoPoint(coords[0], coords[1]) to parts.getOrElse(1) { "Location" }
        } ?: emptyList()
    }
}
