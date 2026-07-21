package com.viewshed.app.viewshed

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
        val target_height_m: Double,
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
                    target_height_m = params.targetHeightM,
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

    internal fun parseGeoJsonViewshed(
        text: String,
        observer: GeoPoint,
        params: ViewshedParams,
    ): ViewshedResult {
        val root = gson.fromJson(text, JsonObject::class.java)
        val features = root.getAsJsonArray("features")
            ?: error("No features in backend response")
        if (features.size() == 0) error("Empty features")
        val feature = features[0].asJsonObject
        val geom =
            feature.getAsJsonObject("geometry")
                ?: error("No geometry")
        val coords =
            geom.getAsJsonArray("coordinates")
                ?: error("No coordinates")
        val geometryType = geom.get("type")?.asString ?: "Polygon"
        val rings =
            when (geometryType) {
                "MultiPolygon" ->
                    (0 until coords.size()).mapNotNull { polygonIndex ->
                        val polygon = coords[polygonIndex].asJsonArray
                        polygon.takeIf { it.size() > 0 }?.get(0)?.asJsonArray
                    }
                "Polygon" -> listOf(coords[0].asJsonArray)
                else -> error("Unsupported backend geometry: $geometryType")
            }
        if (rings.isEmpty()) error("Empty backend geometry")
        val boundaries = rings.map(::parseRing)
        val properties = feature.getAsJsonObject("properties")
        val sectorMetadata = properties?.getAsJsonArray("sectors")
        val sectors =
            if (sectorMetadata != null && sectorMetadata.size() == boundaries.size) {
                boundaries.mapIndexed { index, sectorBoundary ->
                    val metadata = sectorMetadata[index].asJsonObject
                    VisibleSector(
                        bearingStartDeg = metadata.doubleOr("bearing_start_deg", 0.0),
                        bearingEndDeg = metadata.doubleOr("bearing_end_deg", 0.0),
                        innerDistanceM = metadata.doubleOr("inner_distance_m", 0.0),
                        outerDistanceM = metadata.doubleOr("outer_distance_m", 0.0),
                        visibleCellCount = metadata.intOr("visible_cell_count", 0),
                        areaM2 = metadata.doubleOr(
                            "area_m2",
                            GeoMath.polygonAreaKm2(sectorBoundary) * 1_000_000.0,
                        ),
                        boundary = sectorBoundary,
                    )
                }
            } else {
                emptyList()
            }
        val ranges =
            properties?.getAsJsonArray("ranges_m")?.toDoubleList()
                ?: boundaries.first()
                    .dropLast(if (boundaries.first().isClosed()) 1 else 0)
                    .map { point -> GeoMath.distanceM(observer, point) }
        val boundary =
            if (ranges.size == params.numRays) {
                ranges.mapIndexed { index, distanceM ->
                    GeoMath.destination(
                        observer,
                        index * 360.0 / params.numRays,
                        distanceM,
                    )
                }.let { points -> if (points.isEmpty()) points else points + points.first() }
            } else {
                boundaries.first()
            }
        val positive = ranges.filter { it > 0.0 }
        val areaKm2 =
            properties?.doubleOr("visible_area_km2", Double.NaN)
                ?.takeIf { it.isFinite() }
                ?: (sectors.sumOf { it.areaM2 } / 1_000_000.0)
                    .takeIf { it > 0.0 }
                ?: GeoMath.polygonAreaKm2(boundary)
        val visibleCells =
            properties?.intOr("visible_cells", sectors.sumOf { it.visibleCellCount }) ?: 0
        val totalCells =
            properties?.intOr(
                "total_cells",
                params.numRays * params.samplesPerRay,
            ) ?: params.numRays * params.samplesPerRay
        val stats =
            ViewshedStats(
                boundaryPoints = boundary.size,
                maxRangeM = ranges.maxOrNull() ?: 0.0,
                avgRangeM = if (positive.isEmpty()) 0.0 else positive.average(),
                areaKm2 = areaKm2,
                numRays = params.numRays,
                samplesPerRay = params.samplesPerRay,
                visibleCells = visibleCells,
                totalCells = totalCells,
            )
        return ViewshedResult(
            observer = observer,
            boundary = boundary,
            rangesM = ranges,
            stats = stats,
            params = params,
            visibleSectors = sectors,
        )
    }

    private fun parseRing(ring: JsonArray): List<GeoPoint> {
        val boundary = ArrayList<GeoPoint>(ring.size())
        for (index in 0 until ring.size()) {
            val point = ring[index].asJsonArray
            boundary.add(GeoPoint(lat = point[1].asDouble, lon = point[0].asDouble))
        }
        return boundary
    }

    private fun JsonArray.toDoubleList(): List<Double> =
        (0 until size()).map { index -> get(index).asDouble }

    private fun JsonObject.doubleOr(name: String, fallback: Double): Double =
        get(name)?.takeUnless { it.isJsonNull }?.asDouble ?: fallback

    private fun JsonObject.intOr(name: String, fallback: Int): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt ?: fallback

    private fun List<GeoPoint>.isClosed(): Boolean =
        size > 1 && first() == last()

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
