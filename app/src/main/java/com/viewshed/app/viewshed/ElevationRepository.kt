package com.viewshed.app.viewshed

import android.util.Log
import com.viewshed.app.BuildConfig
import com.viewshed.app.data.ElevationDataSources
import com.viewshed.app.viewshed.terrain.TerrainEngine
import com.viewshed.app.viewshed.terrain.TerrainGrid
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

    enum class ElevSource {
        /** Synthetic hills (always offline). */
        DEMO,
        /** Google Elevation API (MAPS_API_KEY). */
        GOOGLE,
        /** Open-Topo-Data USGS 3DEP. */
        USGS_3DEP,
        /** Open-Topo-Data SRTM 90 m. */
        SRTM,
        /** Open-Topo-Data ETOPO1 (includes bathymetry). */
        ETOPO,
        /** Local DEM terrain engine (ASC/CSV or in-memory grid). */
        LOCAL_DEM
    }

    /** Active local DEM from TerrainEngine (set by UI when user loads a file). */
    @Volatile
    var localTerrain: TerrainGrid? = null

    /**
     * @param offline Prefer / fill from [OfflineMapCache] when provided.
     * @param offlineOnly Skip network; use offline pack + demo fallback only.
     * @param source Network elevation product when not demo/offline-only.
     * @param terrain Optional explicit local DEM (defaults to [localTerrain]).
     */
    suspend fun resolveElevations(
        points: List<GeoPoint>,
        useDemo: Boolean,
        offline: OfflineMapCache? = null,
        offlineOnly: Boolean = false,
        source: ElevSource = ElevSource.GOOGLE,
        terrain: TerrainGrid? = localTerrain,
    ): ElevationGrid {
        if (useDemo || source == ElevSource.DEMO) {
            val map = points.associate { it.key() to DemoTerrain.elevation(it) }
            return ElevationGrid(map, useDemo = true)
        }
        if (source == ElevSource.LOCAL_DEM) {
            val dem = terrain ?: TerrainEngine.generateDemoRegion()
            localTerrain = dem
            return TerrainEngine.toElevationGrid(dem, points)
        }
        if (offlineOnly && offline != null) {
            val map = HashMap<String, Double>(points.size)
            for (p in points) {
                map[p.key()] = offline.elevation(p) ?: DemoTerrain.elevation(p)
            }
            return ElevationGrid(map, useDemo = false, terrain = terrain)
        }
        val map = when (source) {
            ElevSource.GOOGLE -> fetchElevationsBatched(points).toMutableMap()
            ElevSource.USGS_3DEP -> fetchOpenTopo(ElevationDataSources.Source.USGS_3DEP, points)
            ElevSource.SRTM -> fetchOpenTopo(ElevationDataSources.Source.SRTM, points)
            ElevSource.ETOPO -> fetchOpenTopo(ElevationDataSources.Source.ETOPO, points)
            ElevSource.DEMO -> points.associate { it.key() to DemoTerrain.elevation(it) }.toMutableMap()
            ElevSource.LOCAL_DEM -> emptyMap<String, Double>().toMutableMap()
        }
        if (offline != null) {
            for (p in points) {
                if (!map.containsKey(p.key())) {
                    offline.elevation(p)?.let { map[p.key()] = it }
                }
            }
        }
        return ElevationGrid(map, useDemo = false, terrain = terrain)
    }

    private suspend fun fetchOpenTopo(
        src: ElevationDataSources.Source,
        points: List<GeoPoint>
    ): MutableMap<String, Double> {
        val remote = ElevationDataSources.fetch(src, points)
        if (remote != null && remote.isNotEmpty()) {
            val map = remote.toMutableMap()
            points.forEach { p -> map.putIfAbsent(p.key(), DemoTerrain.elevation(p)) }
            return map
        }
        Log.w(TAG, "OpenTopo ${src.label} failed — falling back to Google/demo")
        return fetchElevationsBatched(points).toMutableMap()
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
 * Elevation map with optional continuous [terrain] surface + nearest-neighbor fallback.
 */
class ElevationGrid(
    private val byKey: Map<String, Double>,
    val useDemo: Boolean,
    /** Local DEM terrain engine surface (bilinear). Preferred when present. */
    val terrain: com.viewshed.app.viewshed.terrain.TerrainGrid? = null,
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
        // Continuous local DEM first (true terrain engine)
        terrain?.sampleBilinear(point.lat, point.lon)?.let { return it }

        byKey[point.key()]?.let { return it }
        byKey[point.key(5)]?.let { return it }
        byKey[point.key(7)]?.let { return it }

        if (useDemo || samples.isEmpty()) {
            return DemoTerrain.elevation(point)
        }

        var best = Double.MAX_VALUE
        var bestElev: Double? = null
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
