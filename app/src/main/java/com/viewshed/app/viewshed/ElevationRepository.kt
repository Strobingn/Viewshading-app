package com.viewshed.app.viewshed

import com.google.gson.annotations.SerializedName
import com.viewshed.app.BuildConfig
import com.viewshed.app.data.ElevationDataSources
import com.viewshed.app.viewshed.terrain.TerrainEngine
import com.viewshed.app.viewshed.terrain.TerrainGrid
import kotlinx.coroutines.CancellationException
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
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Resolves complete, single-source elevation grids for a calculation. */
class ElevationRepository {

    private val service: ElevationService by lazy {
        Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/maps/api/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build(),
            )
            .build()
            .create(ElevationService::class.java)
    }

    enum class ElevSource {
        DEMO,
        GOOGLE,
        USGS_3DEP,
        SRTM,
        ETOPO,
        LOCAL_DEM,
    }

    @Volatile
    var localTerrain: TerrainGrid? = null

    /**
     * Synthetic terrain is returned only when demo mode is explicitly enabled.
     * Real, local, and offline modes fail closed if any required sample is missing.
     */
    suspend fun resolveElevations(
        points: List<GeoPoint>,
        useDemo: Boolean,
        offline: OfflineMapCache? = null,
        offlineOnly: Boolean = false,
        source: ElevSource = ElevSource.GOOGLE,
        terrain: TerrainGrid? = localTerrain,
    ): ElevationGrid {
        val required = points.distinctBy { it.key() }
        if (useDemo || source == ElevSource.DEMO) {
            return ElevationGrid(
                required.associate { it.key() to DemoTerrain.elevation(it) },
                useDemo = true,
            )
        }
        if (source == ElevSource.LOCAL_DEM) {
            val dem =
                terrain
                    ?: throw ElevationDataException(
                        "Local DEM is selected, but no terrain file is loaded.",
                    )
            return TerrainEngine.toElevationGrid(dem, required)
        }
        if (offlineOnly) {
            val cache =
                offline
                    ?: throw ElevationDataException("Offline elevation is unavailable.")
            val values = HashMap<String, Double>(required.size)
            for (point in required) {
                val elevation =
                    cache.elevation(point)
                        ?: throw ElevationDataException(
                            String.format(
                                Locale.US,
                                "The offline pack does not cover %.5f, %.5f.",
                                point.lat,
                                point.lon,
                            ),
                        )
                values[point.key()] = elevation
            }
            return ElevationGrid(values, useDemo = false)
        }

        val values =
            when (source) {
                ElevSource.GOOGLE -> fetchGoogleElevations(required)
                ElevSource.USGS_3DEP ->
                    fetchOpenTopo(ElevationDataSources.Source.USGS_3DEP, required)
                ElevSource.SRTM ->
                    fetchOpenTopo(ElevationDataSources.Source.SRTM, required)
                ElevSource.ETOPO ->
                    fetchOpenTopo(ElevationDataSources.Source.ETOPO, required)
                ElevSource.DEMO,
                ElevSource.LOCAL_DEM
                -> error("Handled above")
            }
        ensureComplete(required, values, source.name)
        return ElevationGrid(values, useDemo = false)
    }

    private suspend fun fetchOpenTopo(
        source: ElevationDataSources.Source,
        points: List<GeoPoint>,
    ): Map<String, Double> {
        val result = ElevationDataSources.fetch(source, points)
        if (result == null) {
            throw ElevationDataException(
                "${source.label} could not be reached. No demo elevations were substituted.",
            )
        }
        ensureComplete(points, result, source.label)
        return result
    }

    private suspend fun fetchGoogleElevations(points: List<GeoPoint>): Map<String, Double> {
        val apiKey = BuildConfig.MAPS_API_KEY
        if (apiKey.isBlank()) {
            throw ElevationDataException(
                "Real terrain is selected, but this APK has no Elevation API key. " +
                    "Enable Demo terrain or choose a configured data source.",
            )
        }

        val result = HashMap<String, Double>(points.size)
        for (chunk in points.chunked(BATCH_SIZE)) {
            val locations =
                chunk.joinToString("|") {
                    String.format(Locale.US, "%.7f,%.7f", it.lat, it.lon)
                }
            val response =
                try {
                    withContext(Dispatchers.IO) {
                        service.getElevation(locations, apiKey)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw ElevationDataException(
                        "Real elevation download failed. No demo elevations were substituted.",
                        error,
                    )
                }

            if (response.status != "OK") {
                val detail = response.errorMessage?.takeIf { it.isNotBlank() } ?: response.status
                throw ElevationDataException("Elevation API rejected the request: $detail")
            }
            if (response.results.size != chunk.size) {
                throw ElevationDataException(
                    "Elevation API returned ${response.results.size} of ${chunk.size} required points.",
                )
            }
            response.results.forEachIndexed { index, elevation ->
                result[chunk[index].key()] = elevation.elevation
            }
        }
        ensureComplete(points, result, "Google")
        return result
    }

    private fun ensureComplete(
        points: List<GeoPoint>,
        values: Map<String, Double>,
        source: String,
    ) {
        val missing = points.firstOrNull { it.key() !in values }
        if (missing != null) {
            throw ElevationDataException(
                String.format(
                    Locale.US,
                    "%s elevation is missing near %.6f, %.6f. Calculation stopped.",
                    source,
                    missing.lat,
                    missing.lon,
                ),
            )
        }
    }

    private interface ElevationService {
        @GET("elevation/json")
        suspend fun getElevation(
            @Query("locations") locations: String,
            @Query("key") key: String,
        ): ElevationResponse
    }

    private data class ElevationResponse(
        val status: String,
        val results: List<ElevationResult> = emptyList(),
        @SerializedName("error_message") val errorMessage: String? = null,
    )

    private data class ElevationResult(
        val elevation: Double,
        val resolution: Double? = null,
    )

    companion object {
        private const val BATCH_SIZE = 100
    }
}

/** A complete fixed grid or a continuous local DEM surface. */
class ElevationGrid(
    private val byKey: Map<String, Double>,
    val useDemo: Boolean,
    val terrain: TerrainGrid? = null,
) {
    fun elevation(point: GeoPoint): Double {
        terrain?.sampleBilinear(point.lat, point.lon)?.let { return it }
        byKey[point.key()]?.let { return it }
        if (useDemo) return DemoTerrain.elevation(point)
        throw ElevationDataException(
            String.format(
                Locale.US,
                "Elevation is missing near %.6f, %.6f. Calculation stopped.",
                point.lat,
                point.lon,
            ),
        )
    }
}
