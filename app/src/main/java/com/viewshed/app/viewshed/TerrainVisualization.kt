package com.viewshed.app.viewshed

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Phase 3 visualization products generated from a completed viewshed. */
data class VisibilityHeatCell(
    val point: GeoPoint,
    val visibleObserverCount: Int,
    val observerCount: Int,
    val visibilityRatio: Double,
    val normalizedRange: Double,
    val terrainElevationM: Double
)

data class ContourLine(
    val elevationM: Double,
    val points: List<GeoPoint>
)

data class HillshadeCell(
    val point: GeoPoint,
    val intensity: Int
)

data class TerrainVisualizationResult(
    val heatmap: List<VisibilityHeatCell>,
    val contours: List<ContourLine>,
    val hillshade: List<HillshadeCell>,
    val profiles: List<ElevationProfile>
)

/**
 * Produces heat-map, contour, hillshade and elevation-profile data without depending on a
 * particular map SDK. The Android map layer can render these products as polygons, polylines,
 * tile overlays or exported GIS features.
 */
object TerrainVisualization {

    fun build(
        results: List<ViewshedResult>,
        contourIntervalM: Double = 20.0,
        sunAzimuthDeg: Double = 315.0,
        sunAltitudeDeg: Double = 45.0
    ): TerrainVisualizationResult {
        if (results.isEmpty()) {
            return TerrainVisualizationResult(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val samples = mergeSamples(results)
        return TerrainVisualizationResult(
            heatmap = buildHeatmap(results, samples),
            contours = buildContours(samples.values.toList(), contourIntervalM),
            hillshade = buildHillshade(samples.values.toList(), sunAzimuthDeg, sunAltitudeDeg),
            profiles = results.flatMap { it.elevationProfiles }
        )
    }

    private fun mergeSamples(results: List<ViewshedResult>): Map<String, VisibilitySample> {
        val merged = LinkedHashMap<String, VisibilitySample>()
        results.asSequence()
            .flatMap { it.visibilityRays.asSequence() }
            .flatMap { it.samples.asSequence() }
            .forEach { sample -> merged.putIfAbsent(sample.point.key(), sample) }
        return merged
    }

    private fun buildHeatmap(
        results: List<ViewshedResult>,
        samples: Map<String, VisibilitySample>
    ): List<VisibilityHeatCell> {
        val observerCount = results.size.coerceAtLeast(1)
        val visibleCounts = HashMap<String, Int>()
        results.forEach { result ->
            result.visibilityRays.asSequence().flatMap { it.samples.asSequence() }
                .filter { it.visible }
                .map { it.point.key() }
                .distinct()
                .forEach { key -> visibleCounts[key] = (visibleCounts[key] ?: 0) + 1 }
        }
        val maxRange = results.maxOfOrNull { it.params.maxDistKm * 1000.0 }?.coerceAtLeast(1.0) ?: 1.0
        return samples.values.map { sample ->
            val count = visibleCounts[sample.point.key()] ?: 0
            VisibilityHeatCell(
                point = sample.point,
                visibleObserverCount = count,
                observerCount = observerCount,
                visibilityRatio = count.toDouble() / observerCount,
                normalizedRange = (sample.distanceM / maxRange).coerceIn(0.0, 1.0),
                terrainElevationM = sample.terrainElevationM
            )
        }
    }

    /** Marching-squares contours over an inferred regular geographic grid. */
    fun buildContours(samples: List<VisibilitySample>, intervalM: Double): List<ContourLine> {
        if (samples.size < 4 || intervalM <= 0.0) return emptyList()
        val lats = samples.map { it.point.lat }.distinct().sortedDescending()
        val lons = samples.map { it.point.lon }.distinct().sorted()
        if (lats.size < 2 || lons.size < 2 || lats.size * lons.size > samples.size * 4) return emptyList()
        val byKey = samples.associateBy { it.point.key() }
        val minElevation = samples.minOf { it.terrainElevationM }
        val maxElevation = samples.maxOf { it.terrainElevationM }
        var level = floor(minElevation / intervalM) * intervalM
        val output = mutableListOf<ContourLine>()
        while (level <= maxElevation) {
            val segments = mutableListOf<List<GeoPoint>>()
            for (row in 0 until lats.lastIndex) {
                for (column in 0 until lons.lastIndex) {
                    val corners = listOf(
                        GeoPoint(lats[row], lons[column]),
                        GeoPoint(lats[row], lons[column + 1]),
                        GeoPoint(lats[row + 1], lons[column + 1]),
                        GeoPoint(lats[row + 1], lons[column])
                    )
                    val values = corners.map { byKey[it.key()]?.terrainElevationM }
                    if (values.any { it == null }) continue
                    val intersections = mutableListOf<GeoPoint>()
                    for (edge in 0..3) {
                        val next = (edge + 1) % 4
                        val a = values[edge]!!
                        val b = values[next]!!
                        if ((a <= level && b > level) || (a > level && b <= level)) {
                            val t = ((level - a) / (b - a)).coerceIn(0.0, 1.0)
                            intersections += GeoPoint(
                                lat = corners[edge].lat + (corners[next].lat - corners[edge].lat) * t,
                                lon = corners[edge].lon + (corners[next].lon - corners[edge].lon) * t
                            )
                        }
                    }
                    if (intersections.size >= 2) segments += intersections.take(2)
                    if (intersections.size == 4) segments += intersections.drop(2)
                }
            }
            segments.forEach { output += ContourLine(level, it) }
            level += intervalM
        }
        return output
    }

    /** Horn-style local hillshade approximation over neighboring radial samples. */
    fun buildHillshade(
        samples: List<VisibilitySample>,
        sunAzimuthDeg: Double,
        sunAltitudeDeg: Double
    ): List<HillshadeCell> {
        if (samples.size < 3) return emptyList()
        val azimuth = Math.toRadians(360.0 - sunAzimuthDeg + 90.0)
        val zenith = Math.toRadians(90.0 - sunAltitudeDeg)
        val sorted = samples.sortedBy { it.distanceM }
        return sorted.mapIndexed { index, sample ->
            val before = sorted[max(0, index - 1)]
            val after = sorted[min(sorted.lastIndex, index + 1)]
            val distance = (after.distanceM - before.distanceM).coerceAtLeast(1.0)
            val slope = atan((after.terrainElevationM - before.terrainElevationM) / distance)
            val bearing = GeoMath.initialBearing(before.point, after.point)
            val aspect = Math.toRadians(bearing)
            val shade = 255.0 * (
                cos(zenith) * cos(slope) +
                    sin(zenith) * sin(slope) * cos(azimuth - aspect)
                )
            HillshadeCell(sample.point, shade.toInt().coerceIn(0, 255))
        }
    }

    fun heatmapGeoJson(cells: List<VisibilityHeatCell>): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        cells.forEachIndexed { index, cell ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"properties\":{")
            append("\"visibilityRatio\":${cell.visibilityRatio},")
            append("\"visibleObservers\":${cell.visibleObserverCount},")
            append("\"observerCount\":${cell.observerCount},")
            append("\"elevationM\":${cell.terrainElevationM}")
            append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            append(cell.point.lon).append(',').append(cell.point.lat).append("]}}")
        }
        append("]}")
    }
}
