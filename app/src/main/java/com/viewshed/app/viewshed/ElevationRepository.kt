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
    ): Map<String, Double> {
        if (useDemo) {
            return points.associate { it.key() to DemoTerrain.elevation(it) }
        }
        return fetchElevationsBatched(points)
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
            val locations = chunk.joinToString("|") { "${it.lat},${it.lon}" }
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
