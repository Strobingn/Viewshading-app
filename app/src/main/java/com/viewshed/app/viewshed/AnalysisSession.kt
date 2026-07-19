package com.viewshed.app.viewshed

import com.google.gson.Gson

import java.io.File

/**
 * Simple save/load for analysis sessions.
 */
data class AnalysisSession(
    val observer: GeoPoint,
    val maxDistanceM: Double,
    val quality: String,
    val visiblePoints: List<GeoPoint>,
    val timestamp: Long = System.currentTimeMillis()
)

object AnalysisSessionManager {
    private val gson = Gson()

    fun saveSession(session: AnalysisSession, file: File) {
        file.writeText(gson.toJson(session))
    }

    fun loadSession(file: File): AnalysisSession? {
        return try {
            gson.fromJson(file.readText(), AnalysisSession::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
