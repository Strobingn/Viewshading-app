package com.viewshed.app.viewshed

import com.viewshed.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale
import java.util.concurrent.TimeUnit

class ElevationDataException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/** Elevation source: explicitly synthetic demo terrain or complete Google elevation data. */
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
        return ElevationGrid(fetchElevationsBatched(points), useDemo = false)
    }

    private suspend fun fetchElevationsBatched(
        points: List<GeoPoint>
    ): Map<String, Double> {
        val apiKey = BuildConfig.MAPS_API_KEY
        if (apiKey.isBlank()) {
            throw ElevationDataException(
                "Real terrain is selected, but this APK has no Elevation API key. " +
                    "Enable Demo terrain or install a build with MAPS_API_KEY."
            )
        }

        val uniquePoints = points.distinctBy { it.key() }
        val result = HashMap<String, Double>(uniquePoints.size)
        for (chunk in uniquePoints.chunked(BATCH_SIZE)) {
            val locations = chunk.joinToString("|") {
                String.format(Locale.US, "%.7f,%.7f", it.lat, it.lon)
            }
            val response = try {
                withContext(Dispatchers.IO) {
                    service.getElevation(locations, apiKey)
                }
            } catch (error: Exception) {
                throw ElevationDataException(
                    "Real elevation download failed. No demo elevations were substituted.",
                    error
                )
            }

            if (response.status != "OK") {
                val detail = response.errorMessage?.takeIf { it.isNotBlank() }
                    ?: response.status
                throw ElevationDataException(
                    "Elevation API rejected the request: $detail. " +
                        "No demo elevations were substituted."
                )
            }
            if (response.results.size != chunk.size) {
                throw ElevationDataException(
                    "Elevation API returned ${response.results.size} of " +
                        "${chunk.size} required points. Calculation stopped to prevent " +
                        "a mixed real/demo result."
                )
            }

            response.results.forEachIndexed { index, elevation ->
                result[chunk[index].key()] = elevation.elevation
            }
        }

        val missing = uniquePoints.firstOrNull { it.key() !in result }
        if (missing != null) {
            throw ElevationDataException(
                String.format(
                    Locale.US,
                    "Real elevation is missing near %.6f, %.6f. Calculation stopped.",
                    missing.lat,
                    missing.lon
                )
            )
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
        val results: List<ElevationResult> = emptyList(),
        @com.google.gson.annotations.SerializedName("error_message")
        val errorMessage: String? = null
    )

    private data class ElevationResult(
        val elevation: Double,
        val resolution: Double? = null
    )

    companion object {
        private const val BATCH_SIZE = 100
    }
}

/**
 * Complete elevation grid for one calculation.
 *
 * Real mode fails on a missing point. It never substitutes synthetic terrain or a
 * neighboring elevation, because either behavior can change the line-of-sight result.
 */
class ElevationGrid(
    private val byKey: Map<String, Double>,
    val useDemo: Boolean
) {
    fun elevation(point: GeoPoint): Double {
        byKey[point.key()]?.let { return it }
        if (useDemo) {
            return DemoTerrain.elevation(point)
        }
        throw ElevationDataException(
            String.format(
                Locale.US,
                "Real elevation is missing near %.6f, %.6f. " +
                    "Calculation stopped instead of using fake terrain.",
                point.lat,
                point.lon
            )
        )
    }
}
