package com.viewshed.app.viewshed

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to the Oracle Cloud / Docker FastAPI backend (POST /viewshed).
 */
class BackendViewshedClient(
    private val baseUrl: String,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build(),
    private val gson: Gson = Gson(),
) {
    data class ObserverBody(val lat: Double, val lon: Double, val height_m: Double)
    data class RequestBody(
        val observer: ObserverBody,
        val max_distance_m: Double,
        val num_rays: Int,
        val samples_per_ray: Int,
        val refraction_coeff: Double,
        val use_curvature: Boolean,
    )

    suspend fun health(): Boolean =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/health"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    suspend fun compute(observer: GeoPoint, params: ViewshedParams): ViewshedResult =
        withContext(Dispatchers.IO) {
            val body =
                RequestBody(
                    observer =
                        ObserverBody(
                            lat = observer.lat,
                            lon = observer.lon,
                            height_m = params.eyeHeightM,
                        ),
                    max_distance_m = params.maxDistKm * 1000.0,
                    num_rays = params.numRays,
                    samples_per_ray = params.samplesPerRay,
                    refraction_coeff = params.refraction,
                    use_curvature = params.useCurvature,
                )
            val json = gson.toJson(body)
            val url = baseUrl.trimEnd('/') + "/viewshed"
            val req =
                Request.Builder()
                    .url(url)
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Backend HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                }
                val text = resp.body?.string() ?: error("Empty backend body")
                parseGeoJsonViewshed(text, observer, params)
            }
        }

    private fun parseGeoJsonViewshed(
        text: String,
        observer: GeoPoint,
        params: ViewshedParams,
    ): ViewshedResult {
        val root = gson.fromJson(text, JsonObject::class.java)
        val features = root.getAsJsonArray("features")
            ?: error("No features in backend response")
        if (features.size() == 0) error("Empty features")
        val geom =
            features[0].asJsonObject.getAsJsonObject("geometry")
                ?: error("No geometry")
        val coords =
            geom.getAsJsonArray("coordinates")
                ?: error("No coordinates")
        // Polygon: coordinates[0] = outer ring of [lon, lat]
        val ring = coords[0].asJsonArray
        val boundary = ArrayList<GeoPoint>(ring.size())
        for (i in 0 until ring.size()) {
            val pt = ring[i].asJsonArray
            val lon = pt[0].asDouble
            val lat = pt[1].asDouble
            boundary.add(GeoPoint(lat, lon))
        }
        // ranges: approximate from observer to each vertex
        val ranges =
            boundary.dropLast(if (boundary.size > 1 && boundary.first() == boundary.last()) 1 else 0)
                .map { p -> GeoMath.distanceM(observer, p) }
        val positive = ranges.filter { it > 0 }
        val stats =
            ViewshedStats(
                boundaryPoints = boundary.size,
                maxRangeM = ranges.maxOrNull() ?: 0.0,
                avgRangeM = if (positive.isEmpty()) 0.0 else positive.average(),
                areaKm2 = GeoMath.polygonAreaKm2(boundary),
                numRays = params.numRays,
                samplesPerRay = params.samplesPerRay,
            )
        return ViewshedResult(
            observer = observer,
            boundary = boundary,
            rangesM = ranges,
            stats = stats,
            params = params,
        )
    }

    companion object {
        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "http://$u"
            }
            return u.trimEnd('/')
        }
    }
}
