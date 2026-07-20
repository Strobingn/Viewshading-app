package com.viewshed.app.viewshed

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Phase 3 visualization products generated from a completed viewshed. */
data class VisibilityHeatCell(
    val point: GeoPoint,
    val visibleObserverCount: Int,
    val observerCount: Int,
    val visibilityRatio: Double,
    val normalizedRange: Double,
    val terrainElevationM: Double
)

data class ContourLine(val elevationM: Double, val points: List<GeoPoint>)
data class HillshadeCell(val point: GeoPoint, val intensity: Int)

data class TerrainVisualizationResult(
    val heatmap: List<VisibilityHeatCell>,
    val contours: List<ContourLine>,
    val hillshade: List<HillshadeCell>,
    val profiles: List<ElevationProfile>
)

/** Map-SDK-independent heatmap, contour, hillshade and profile generation. */
object TerrainVisualization {
    fun build(
        results: List<ViewshedResult>,
        contourIntervalM: Double = 20.0,
        sunAzimuthDeg: Double = 315.0,
        sunAltitudeDeg: Double = 45.0
    ): TerrainVisualizationResult {
        if (results.isEmpty()) return TerrainVisualizationResult(emptyList(), emptyList(), emptyList(), emptyList())
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
        results.asSequence().flatMap { it.visibilityRays.asSequence() }
            .flatMap { it.samples.asSequence() }
            .forEach { merged.putIfAbsent(it.point.key(), it) }
        return merged
    }

    private fun buildHeatmap(results: List<ViewshedResult>, samples: Map<String, VisibilitySample>): List<VisibilityHeatCell> {
        val observerCount = results.size.coerceAtLeast(1)
        val counts = HashMap<String, Int>()
        results.forEach { result ->
            result.visibilityRays.asSequence().flatMap { it.samples.asSequence() }
                .filter { it.visible }.map { it.point.key() }.distinct()
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val maxRange = results.maxOfOrNull { it.params.maxDistKm * 1000.0 }?.coerceAtLeast(1.0) ?: 1.0
        return samples.values.map { sample ->
            val count = counts[sample.point.key()] ?: 0
            VisibilityHeatCell(sample.point, count, observerCount, count.toDouble() / observerCount,
                (sample.distanceM / maxRange).coerceIn(0.0, 1.0), sample.terrainElevationM)
        }
    }

    /** Marching-squares contours over a regular geographic sample grid. */
    fun buildContours(samples: List<VisibilitySample>, intervalM: Double): List<ContourLine> {
        if (samples.size < 4 || intervalM <= 0.0) return emptyList()
        val lats = samples.map { it.point.lat }.distinct().sortedDescending()
        val lons = samples.map { it.point.lon }.distinct().sorted()
        if (lats.size < 2 || lons.size < 2 || lats.size * lons.size > samples.size * 4) return emptyList()
        val byKey = samples.associateBy { it.point.key() }
        var level = floor(samples.minOf { it.terrainElevationM } / intervalM) * intervalM
        val maxLevel = samples.maxOf { it.terrainElevationM }
        val output = mutableListOf<ContourLine>()
        while (level <= maxLevel) {
            for (row in 0 until lats.lastIndex) for (column in 0 until lons.lastIndex) {
                val corners = listOf(
                    GeoPoint(lats[row], lons[column]), GeoPoint(lats[row], lons[column + 1]),
                    GeoPoint(lats[row + 1], lons[column + 1]), GeoPoint(lats[row + 1], lons[column])
                )
                val values = corners.map { byKey[it.key()]?.terrainElevationM }
                if (values.any { it == null }) continue
                val hits = mutableListOf<GeoPoint>()
                for (edge in 0..3) {
                    val next = (edge + 1) % 4
                    val a = values[edge]!!; val b = values[next]!!
                    if ((a <= level && b > level) || (a > level && b <= level)) {
                        val t = ((level - a) / (b - a)).coerceIn(0.0, 1.0)
                        hits += GeoPoint(corners[edge].lat + (corners[next].lat - corners[edge].lat) * t,
                            corners[edge].lon + (corners[next].lon - corners[edge].lon) * t)
                    }
                }
                if (hits.size >= 2) output += ContourLine(level, hits.take(2))
                if (hits.size == 4) output += ContourLine(level, hits.drop(2))
            }
            level += intervalM
        }
        return output
    }

    fun buildHillshade(samples: List<VisibilitySample>, sunAzimuthDeg: Double, sunAltitudeDeg: Double): List<HillshadeCell> {
        if (samples.size < 3) return emptyList()
        val azimuth = Math.toRadians(360.0 - sunAzimuthDeg + 90.0)
        val zenith = Math.toRadians(90.0 - sunAltitudeDeg)
        val sorted = samples.sortedBy { it.distanceM }
        return sorted.mapIndexed { index, sample ->
            val before = sorted[max(0, index - 1)]; val after = sorted[min(sorted.lastIndex, index + 1)]
            val slope = atan((after.terrainElevationM - before.terrainElevationM) /
                (after.distanceM - before.distanceM).coerceAtLeast(1.0))
            val aspect = Math.toRadians(initialBearing(before.point, after.point))
            val shade = 255.0 * (cos(zenith) * cos(slope) + sin(zenith) * sin(slope) * cos(azimuth - aspect))
            HillshadeCell(sample.point, shade.toInt().coerceIn(0, 255))
        }
    }

    private fun initialBearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        return GeoMath.clampBearing(Math.toDegrees(atan2(sin(dLon) * cos(lat2),
            cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon))))
    }

    fun heatmapGeoJson(cells: List<VisibilityHeatCell>): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        cells.forEachIndexed { index, cell ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"properties\":{")
            append("\"visibilityRatio\":${cell.visibilityRatio},\"visibleObservers\":${cell.visibleObserverCount},")
            append("\"observerCount\":${cell.observerCount},\"elevationM\":${cell.terrainElevationM}")
            append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[${cell.point.lon},${cell.point.lat}]}}")
        }
        append("]}")
    }
}
