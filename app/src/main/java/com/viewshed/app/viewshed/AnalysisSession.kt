package com.viewshed.app.viewshed

import com.google.gson.Gson
import java.io.File

/** Save/load observer parameters and the sampled visibility result. */
data class AnalysisSession(
    val observerLat: Double,
    val observerLon: Double,
    val maxDistKm: Double,
    val eyeHeightM: Double,
    val quality: String,
    val boundary: List<GeoPoint>,
    val rangesM: List<Double> = emptyList(),
    val visibleSectors: List<VisibleSector> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromResult(result: ViewshedResult): AnalysisSession =
            AnalysisSession(
                observerLat = result.observer.lat,
                observerLon = result.observer.lon,
                maxDistKm = result.params.maxDistKm,
                eyeHeightM = result.params.eyeHeightM,
                quality = result.params.quality.name,
                boundary = result.boundary,
                rangesM = result.rangesM,
                visibleSectors = result.visibleSectors
            )
    }
}

object AnalysisSessionManager {
    private val gson = Gson()

    fun save(session: AnalysisSession, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(session))
    }

    fun load(file: File): AnalysisSession? = try {
        gson.fromJson(file.readText(), AnalysisSession::class.java)
    } catch (_: Exception) {
        null
    }
}
