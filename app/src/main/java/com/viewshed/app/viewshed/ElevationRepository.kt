package com.viewshed.app.viewshed

import android.util.Log
import com.viewshed.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Elevation source: demo terrain or Google Elevation API.
 * Lookups support exact keys plus nearest-neighbor so adaptive / binary-search
 * distances (not on the pre-fetch lattice) still use real elevations.
 */
class ElevationRepository {

    private val service: ElevationService by lazy {
        Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/maps/api/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            )
            .build()
            .create(ElevationService::class.java)
    }

    suspend fun resolveElevations(
        points: List<GeoPoint>,
        useDemo: Boolean
    ): ElevationGrid {
        if (useDemo) {
            val map = points.associate { it.key() to DemoTerrain.elevation(it) }
            return ElevationGrid(map, useDemo = true)
        }
        val map = fetchElevationsBatched(points)
        return ElevationGrid(map, useDemo = false)
    }

    private suspend fun fetchElevationsBatched(points: List<GeoPoint>): Map<String, Double> {
        val result = HashMap<String, Double>(points.size)
        val key = BuildConfig.MAPS_API_KEY
        if (key.isBlank()) {
            Log.w(TAG, "No MAPS_API_KEY — demo elevations")
            points.forEach { result[it.key()] = DemoTerrain.elevation(it) }
            return result
        }

        val unique = points.distinctBy { it.key() }
        for (chunk in unique.chunked(BATCH_SIZE)) {
            // API requires lat,lng with dots (US format)
            val locations = chunk.joinToString("|") {
                String.format(java.util.Locale.US, "%.7f,%.7f", it.lat, it.lon)
            }
            try {
                val response = withContext(Dispatchers.IO) {
                    service.getElevation(locations, key)
                }
                if (response.status == "OK" && response.results.isNotEmpty()) {
                    response.results.forEachIndexed { idx, elev ->
                        if (idx < chunk.size) {
                            result[chunk[idx].key()] = elev.elevation
                        }
                    }
                } else {
                    Log.w(TAG, "Elevation API: ${response.status}")
                    chunk.forEach { result[it.key()] = DemoTerrain.elevation(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Elevation batch failed", e)
                chunk.forEach { result[it.key()] = DemoTerrain.elevation(it) }
            }
        }

        points.forEach { p ->
            result.putIfAbsent(p.key(), DemoTerrain.elevation(p))
        }
        return result
    }

    private interface ElevationService {
        @GET("elevation/json")
        suspend fun getElevation(
            @Query("locations") locations: String,
            @Query("key") key: String
        ): ElevationResponse
    }

    private data class ElevationResponse(
        val status: String,
        val results: List<ElevationResult> = emptyList()
    )

    private data class ElevationResult(
        val elevation: Double,
        val resolution: Double? = null
    )

    companion object {
        private const val TAG = "ElevationRepo"
        private const val BATCH_SIZE = 100
    }
}

/**
 * Elevation map with nearest-neighbor fallback for off-lattice sample points.
 */
class ElevationGrid(
    private val byKey: Map<String, Double>,
    val useDemo: Boolean
) {
    private val samples: List<Pair<GeoPoint, Double>> by lazy {
        byKey.mapNotNull { (k, elev) ->
            val parts = k.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(lat, lon) to elev
        }
    }

    fun elevation(point: GeoPoint, maxNeighborM: Double = 75.0): Double {
        byKey[point.key()]?.let { return it }
        // Tighter / looser keys
        byKey[point.key(5)]?.let { return it }
        byKey[point.key(7)]?.let { return it }

        if (useDemo || samples.isEmpty()) {
            return DemoTerrain.elevation(point)
        }

        var best = Double.MAX_VALUE
        var bestElev: Double? = null
        // Cheap local degree box then haversine-ish meters
        val latScale = 111_320.0
        val lonScale = 111_320.0 * cos(Math.toRadians(point.lat)).coerceAtLeast(0.2)
        val maxDegLat = maxNeighborM / latScale
        val maxDegLon = maxNeighborM / lonScale

        for ((p, elev) in samples) {
            val dLat = kotlin.math.abs(p.lat - point.lat)
            val dLon = kotlin.math.abs(p.lon - point.lon)
            if (dLat > maxDegLat || dLon > maxDegLon) continue
            val dy = dLat * latScale
            val dx = dLon * lonScale
            val d = sqrt(dx * dx + dy * dy)
            if (d < best) {
                best = d
                bestElev = elev
            }
        }
        return bestElev ?: DemoTerrain.elevation(point)
    }
}
