package com.viewshed.app.data

import com.viewshed.app.viewshed.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phase 6 — Additional free elevation / DEM-style sources.
 *
 * Open-Topo-Data aggregates USGS 3DEP, SRTM, and others without requiring
 * a Google Elevation key. Failures return null so callers fall back.
 */
object ElevationDataSources {

    enum class Source(val path: String, val label: String) {
        USGS_3DEP("ned10m", "USGS 3DEP (10 m)"),
        SRTM("srtm90m", "NASA SRTM 90 m"),
        ASTER("aster30m", "ASTER 30 m"),
        ETOPO("etopo1", "NOAA ETOPO1 (bathymetry/topo)")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch elevations for points from Open-Topo-Data.
     * API: https://api.opentopodata.org/v1/{dataset}?locations=lat,lon|...
     */
    suspend fun fetch(
        source: Source,
        points: List<GeoPoint>
    ): Map<String, Double>? = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyMap()
        val unique = points.distinctBy { it.key() }
        val out = HashMap<String, Double>(unique.size)
        try {
            for (chunk in unique.chunked(100)) {
                val locs = chunk.joinToString("|") {
                    String.format(java.util.Locale.US, "%.6f,%.6f", it.lat, it.lon)
                }
                val url = "https://api.opentopodata.org/v1/${source.path}?locations=$locs"
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    if (json.optString("status") != "OK") return@withContext null
                    val results = json.getJSONArray("results")
                    for (i in 0 until results.length()) {
                        val r = results.getJSONObject(i)
                        if (r.isNull("elevation")) continue
                        val elev = r.getDouble("elevation")
                        val loc = r.getJSONObject("location")
                        val lat = loc.getDouble("lat")
                        val lon = loc.getDouble("lng")
                        out[GeoPoint(lat, lon).key()] = elev
                    }
                }
            }
            out
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
