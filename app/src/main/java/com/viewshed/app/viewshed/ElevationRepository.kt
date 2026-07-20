package com.viewshed.app.viewshed

import com.viewshed.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ElevationDataException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Elevation source supporting local DEM, synthetic demo terrain, or Google Elevation. */
class ElevationRepository {
    @Volatile private var localDem: DemSource? = null
    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()

    private val service: ElevationService by lazy {
        Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/maps/api/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build())
            .build().create(ElevationService::class.java)
    }

    fun setLocalDem(source: DemSource?) {
        localDem = source
    }

    fun hasLocalDem(): Boolean = localDem != null

    suspend fun resolveElevations(points: List<GeoPoint>, useDemo: Boolean): ElevationGrid {
        val dem = localDem
        if (dem != null) {
            val values = HashMap<String, Double>(points.size)
            for (point in points.distinctBy { it.key() }) {
                values[point.key()] = dem.elevation(point)
                    ?: throw ElevationDataException("Selected DEM does not cover %.6f, %.6f".format(Locale.US, point.lat, point.lon))
            }
            return ElevationGrid(values, useDemo = false, sourceName = "Local DEM")
        }
        if (useDemo) {
            return ElevationGrid(points.associate { it.key() to DemoTerrain.elevation(it) }, true, "Demo terrain")
        }
        return ElevationGrid(fetchElevationsBatched(points), false, "Google Elevation")
    }

    private suspend fun fetchElevationsBatched(points: List<GeoPoint>): Map<String, Double> {
        val apiKey = BuildConfig.MAPS_API_KEY
        if (apiKey.isBlank()) throw ElevationDataException("Real terrain is selected, but this APK has no Elevation API key.")
        purgeExpiredCache()
        val unique = points.distinctBy { it.key() }
        val result = ConcurrentHashMap<String, Double>()
        val missing = unique.filter { point ->
            memoryCache[point.key()]?.let { result[point.key()] = it.elevation; true } != true
        }
        val semaphore = Semaphore(MAX_CONCURRENT_BATCHES)
        coroutineScope {
            missing.chunked(BATCH_SIZE).map { chunk ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { fetchChunk(chunk, apiKey) }
                }
            }.awaitAll().forEach { batch -> result.putAll(batch) }
        }
        unique.firstOrNull { it.key() !in result }?.let {
            throw ElevationDataException("Real elevation is missing near %.6f, %.6f".format(Locale.US, it.lat, it.lon))
        }
        return result
    }

    private suspend fun fetchChunk(chunk: List<GeoPoint>, apiKey: String): Map<String, Double> {
        val locations = chunk.joinToString("|") { String.format(Locale.US, "%.7f,%.7f", it.lat, it.lon) }
        val response = try {
            withContext(Dispatchers.IO) { service.getElevation(locations, apiKey) }
        } catch (error: Exception) {
            throw ElevationDataException("Real elevation download failed.", error)
        }
        if (response.status != "OK") {
            throw ElevationDataException("Elevation API rejected the request: ${response.errorMessage ?: response.status}")
        }
        if (response.results.size != chunk.size) {
            throw ElevationDataException("Elevation API returned ${response.results.size} of ${chunk.size} points")
        }
        return chunk.indices.associate { index ->
            val key = chunk[index].key()
            val value = response.results[index].elevation
            memoryCache[key] = CacheEntry(value, System.currentTimeMillis())
            key to value
        }
    }

    private fun purgeExpiredCache() {
        val cutoff = System.currentTimeMillis() - CACHE_EXPIRATION_MS
        memoryCache.entries.removeIf { it.value.timestamp < cutoff }
    }

    private interface ElevationService {
        @GET("elevation/json")
        suspend fun getElevation(@Query("locations") locations: String, @Query("key") key: String): ElevationResponse
    }

    private data class ElevationResponse(
        val status: String,
        val results: List<ElevationResult> = emptyList(),
        @com.google.gson.annotations.SerializedName("error_message") val errorMessage: String? = null
    )
    private data class ElevationResult(val elevation: Double, val resolution: Double? = null)
    private data class CacheEntry(val elevation: Double, val timestamp: Long)

    companion object {
        private const val BATCH_SIZE = 100
        private const val MAX_CONCURRENT_BATCHES = 4
        private const val CACHE_EXPIRATION_MS = 24L * 60 * 60 * 1000
    }
}

class ElevationGrid(
    private val byKey: Map<String, Double>,
    val useDemo: Boolean,
    val sourceName: String = if (useDemo) "Demo terrain" else "Elevation data"
) {
    fun elevation(point: GeoPoint): Double {
        byKey[point.key()]?.let { return it }
        if (useDemo) return DemoTerrain.elevation(point)
        throw ElevationDataException("Elevation is missing near %.6f, %.6f".format(Locale.US, point.lat, point.lon))
    }
}
