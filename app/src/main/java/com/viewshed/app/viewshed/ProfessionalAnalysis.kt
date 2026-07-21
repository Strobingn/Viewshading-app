package com.viewshed.app.viewshed

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 4 — Professional GIS-style analysis (intervisibility, cumulative,
 * frequency, path LOS, weighted viewshed, solar shadow).
 *
 * Pure Kotlin; works with [ElevationGrid] + [ViewshedResult] from the core engine.
 */
object ProfessionalAnalysis {

    private const val ANGLE_EPS = 1e-9

    data class IntervisibilityResult(
        val from: GeoPoint,
        val to: GeoPoint,
        val visible: Boolean,
        val distanceM: Double,
        val sampleCount: Int,
        val firstBlockDistM: Double?
    )

    data class PathVisibilityResult(
        val path: List<GeoPoint>,
        val segmentVisible: List<Boolean>,
        val visibleFraction: Double,
        val totalLengthM: Double,
        val visibleLengthM: Double
    )

    data class FrequencyCell(
        val point: GeoPoint,
        val count: Int,
        val weight: Double
    )

    data class CumulativeViewshed(
        val cells: List<FrequencyCell>,
        val maxCount: Int,
        val unionApproxKm2: Double,
        val observerCount: Int
    )

    data class WeightedViewshedStats(
        val rawAreaKm2: Double,
        val weightedScore: Double,
        val nearAreaKm2: Double,
        val farAreaKm2: Double
    )

    data class ShadowResult(
        val solar: SolarPos,
        val shadowBoundary: List<GeoPoint>,
        val shadowRangesM: List<Double>,
        val sunUp: Boolean
    )

    // --- Intervisibility (point-to-point LOS) ---

    fun intervisibility(
        from: GeoPoint,
        to: GeoPoint,
        elevations: ElevationGrid,
        eyeHeightM: Double = 1.7,
        targetHeightM: Double = 1.7,
        samples: Int = 80,
        useCurvature: Boolean = true,
        refraction: Double = GeoMath.DEFAULT_REFRACTION,
    ): IntervisibilityResult {
        val dist = GeoMath.distanceM(from, to)
        if (dist < 1.0) {
            return IntervisibilityResult(from, to, true, dist, 0, null)
        }
        val k = GeoMath.clampRefraction(refraction)
        val eye = elevations.elevation(from) + eyeHeightM
        val targetElev = elevations.elevation(to) + targetHeightM
        val bearing = GeoMath.bearingDeg(from, to)
        val n = samples.coerceIn(8, 250)
        var horizon = Double.NEGATIVE_INFINITY
        var firstBlock: Double? = null

        // Pure max-elevation-angle LOS: intermediate samples only raise the horizon.
        // A sample below the running max does not block by itself (it sits behind nearer terrain).
        for (i in 1 until n) {
            val d = dist * i / n
            val p = GeoMath.destination(from, bearing, d)
            val ground = elevations.elevation(p)
            val angle = GeoMath.elevationAngleRad(eye, ground, d, useCurvature, k)
            if (angle > horizon + ANGLE_EPS) {
                horizon = angle
            }
        }
        val targetAngle = GeoMath.elevationAngleRad(eye, targetElev, dist, useCurvature, k)
        val visible = targetAngle >= horizon - ANGLE_EPS
        if (!visible) {
            // First intermediate where horizon already exceeds the final target angle.
            var h = Double.NEGATIVE_INFINITY
            for (i in 1 until n) {
                val d = dist * i / n
                val p = GeoMath.destination(from, bearing, d)
                val ground = elevations.elevation(p)
                val angle = GeoMath.elevationAngleRad(eye, ground, d, useCurvature, k)
                if (angle > h + ANGLE_EPS) h = angle
                if (h > targetAngle + ANGLE_EPS) {
                    firstBlock = d
                    break
                }
            }
        }
        return IntervisibilityResult(
            from = from,
            to = to,
            visible = visible,
            distanceM = dist,
            sampleCount = n,
            firstBlockDistM = firstBlock,
        )
    }

    // --- Point-in-viewshed using radial ranges ---

    fun isVisibleInViewshed(result: ViewshedResult, point: GeoPoint): Boolean {
        val p = result.params
        val dist = GeoMath.distanceM(result.observer, point)
        val maxM = p.maxDistKm * 1000.0
        if (dist > maxM * 1.001) return false
        if (dist < 1.0) return true
        val bearing = GeoMath.bearingDeg(result.observer, point)
        val ranges = result.rangesM
        if (ranges.isEmpty()) return false
        val n = ranges.size
        val idxF = (bearing / 360.0) * n
        val i0 = idxF.toInt().mod(n)
        val i1 = (i0 + 1).mod(n)
        val t = idxF - idxF.toInt()
        val range = ranges[i0] * (1 - t) + ranges[i1] * t
        return dist <= range + 1.0
    }

    // --- Visibility frequency / cumulative multi-observer ---

    fun cumulativeViewshed(
        results: List<ViewshedResult>,
        gridSteps: Int = 28
    ): CumulativeViewshed {
        if (results.isEmpty()) {
            return CumulativeViewshed(emptyList(), 0, 0.0, 0)
        }
        val allPts = results.flatMap { it.boundary + it.observer }
        val minLat = allPts.minOf { it.lat }
        val maxLat = allPts.maxOf { it.lat }
        val minLon = allPts.minOf { it.lon }
        val maxLon = allPts.maxOf { it.lon }
        val n = gridSteps.coerceIn(8, 64)
        val cells = ArrayList<FrequencyCell>(n * n)
        var maxCount = 0
        var visibleCells = 0

        for (i in 0..n) {
            val lat = minLat + (maxLat - minLat) * i / n
            for (j in 0..n) {
                val lon = minLon + (maxLon - minLon) * j / n
                val pt = GeoPoint(lat, lon)
                var count = 0
                var weight = 0.0
                for (r in results) {
                    if (isVisibleInViewshed(r, pt)) {
                        count++
                        val d = GeoMath.distanceM(r.observer, pt)
                        val maxM = r.params.maxDistKm * 1000.0
                        // Distance weight: nearer visibility counts more
                        weight += if (maxM > 0) 1.0 - (d / maxM).coerceIn(0.0, 1.0) * 0.7 else 1.0
                    }
                }
                if (count > 0) {
                    cells.add(FrequencyCell(pt, count, weight))
                    maxCount = max(maxCount, count)
                    visibleCells++
                }
            }
        }
        // Approximate union area from bounding box × visible cell ratio
        val box = listOf(
            GeoPoint(minLat, minLon),
            GeoPoint(minLat, maxLon),
            GeoPoint(maxLat, maxLon),
            GeoPoint(maxLat, minLon)
        )
        val boxKm2 = GeoMath.polygonAreaKm2(box)
        val ratio = visibleCells.toDouble() / ((n + 1) * (n + 1))
        return CumulativeViewshed(
            cells = cells,
            maxCount = maxCount,
            unionApproxKm2 = boxKm2 * ratio,
            observerCount = results.size
        )
    }

    // --- Distance-weighted viewshed score ---

    fun weightedStats(result: ViewshedResult): WeightedViewshedStats {
        val maxM = result.params.maxDistKm * 1000.0
        val mid = maxM * 0.4
        var near = 0.0
        var far = 0.0
        var score = 0.0
        val n = result.rangesM.size.coerceAtLeast(1)
        val sector = 2 * Math.PI / n
        for (range in result.rangesM) {
            val r = range.coerceAtLeast(0.0)
            // Sector area ≈ 0.5 * r² * θ
            val areaM2 = 0.5 * r * r * sector
            score += areaM2 * (1.0 - 0.5 * (r / maxM.coerceAtLeast(1.0)).coerceIn(0.0, 1.0))
            if (r <= mid) near += areaM2 else far += areaM2
        }
        return WeightedViewshedStats(
            rawAreaKm2 = result.stats.areaKm2,
            weightedScore = score / 1_000_000.0,
            nearAreaKm2 = near / 1_000_000.0,
            farAreaKm2 = far / 1_000_000.0
        )
    }

    // --- Path visibility (route LOS from observer) ---

    fun pathVisibility(
        observer: GeoPoint,
        path: List<GeoPoint>,
        elevations: ElevationGrid,
        eyeHeightM: Double = 1.7,
        targetHeightM: Double = 1.0,
        samplesPerSegment: Int = 24,
        useCurvature: Boolean = true,
        refraction: Double = GeoMath.DEFAULT_REFRACTION,
    ): PathVisibilityResult {
        if (path.isEmpty()) {
            return PathVisibilityResult(path, emptyList(), 0.0, 0.0, 0.0)
        }
        val segs = ArrayList<Boolean>(path.size)
        var total = 0.0
        var visibleLen = 0.0
        for (pt in path) {
            val iv = intervisibility(
                from = observer,
                to = pt,
                elevations = elevations,
                eyeHeightM = eyeHeightM,
                targetHeightM = targetHeightM,
                samples = samplesPerSegment,
                useCurvature = useCurvature,
                refraction = refraction
            )
            segs.add(iv.visible)
            total += iv.distanceM
            if (iv.visible) visibleLen += iv.distanceM
        }
        // Also check consecutive path segments
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val segLen = GeoMath.distanceM(a, b)
            total += segLen
            val mid = GeoMath.destination(a, GeoMath.bearingDeg(a, b), segLen / 2)
            val iv = intervisibility(
                observer, mid, elevations, eyeHeightM, targetHeightM,
                samplesPerSegment, useCurvature, refraction
            )
            if (iv.visible) visibleLen += segLen
        }
        val frac = if (total > 0) (visibleLen / total).coerceIn(0.0, 1.0) else 0.0
        return PathVisibilityResult(path, segs, frac, total, visibleLen)
    }

    /**
     * Sun-direction terrain occlusion (cast shadow along the solar azimuth).
     *
     * Marches from the observer **away from the sun** (bearing = sunAz + 180°)
     * and stops at the first terrain elevation angle that exceeds solar altitude.
     * That distance is the local shadow length. Optional side fans (±fanHalfWidthDeg)
     * produce a simple wedge boundary for map display — not full DEM radiosity.
     */
    fun shadowAnalysis(
        observer: GeoPoint,
        elevations: ElevationGrid,
        params: ViewshedParams,
        timeMs: Long = System.currentTimeMillis(),
        numRays: Int = 72,
        fanHalfWidthDeg: Double = 35.0,
    ): ShadowResult {
        val solar = SolarPosition.at(observer.lat, observer.lon, timeMs)
        if (!solar.isUp) {
            return ShadowResult(solar, emptyList(), emptyList(), sunUp = false)
        }
        val p = params.sanitized()
        val maxDistM = p.maxDistKm * 1000.0
        val eye = elevations.elevation(observer) + p.eyeHeightM
        val sunAltRad = Math.toRadians(solar.altitudeDeg)
        val rays = numRays.coerceIn(16, 180)
        val shadowBearing = GeoMath.clampBearing(solar.azimuthDeg + 180.0)
        val fan = fanHalfWidthDeg.coerceIn(5.0, 90.0)

        // Primary shadow length along anti-solar bearing.
        val primaryLength = marchSunOcclusion(
            observer, shadowBearing, eye, maxDistM, p, elevations, sunAltRad,
        )

        // Fan of rays around the anti-solar direction for a map wedge.
        val ranges = ArrayList<Double>(rays)
        val boundary = ArrayList<GeoPoint>(rays + 1)
        for (i in 0 until rays) {
            val t = if (rays == 1) 0.0 else i.toDouble() / (rays - 1)
            val bearing = GeoMath.clampBearing(shadowBearing - fan + t * (2.0 * fan))
            val angularOff = absBearingDelta(bearing, shadowBearing)
            // Length falls off toward the fan edges (simple cosine taper).
            val taper = cos(Math.toRadians((angularOff / fan) * 90.0)).coerceIn(0.0, 1.0)
            val maxAlong = maxDistM * (0.25 + 0.75 * taper)
            val length = marchSunOcclusion(
                observer, bearing, eye, maxAlong.coerceAtMost(primaryLength * (0.4 + 0.6 * taper) + maxDistM * 0.05),
                p, elevations, sunAltRad,
            )
            ranges.add(length)
            boundary.add(
                if (length > 1.0) GeoMath.destination(observer, bearing, length)
                else GeoMath.destination(observer, bearing, 1.0),
            )
        }
        if (boundary.isNotEmpty()) boundary.add(boundary.first())
        return ShadowResult(solar, boundary, ranges, sunUp = true)
    }

    /** March until terrain elev angle exceeds solar altitude (sun blocked by terrain). */
    private fun marchSunOcclusion(
        observer: GeoPoint,
        bearing: Double,
        eye: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: ElevationGrid,
        sunAltRad: Double,
    ): Double {
        var lastLit = 0.0
        val samples = p.samplesPerRay.coerceAtLeast(10)
        val limit = maxDistM.coerceAtLeast(1.0)
        for (s in 1..samples) {
            val d = s * limit / samples
            val pt = GeoMath.destination(observer, bearing, d)
            val ground = elevations.elevation(pt)
            val elevAngle = GeoMath.elevationAngleRad(
                eye, ground, d, p.useCurvature, p.refraction,
            )
            if (elevAngle > sunAltRad + ANGLE_EPS) {
                return lastLit
            }
            lastLit = d
        }
        return lastLit
    }

    private fun absBearingDelta(a: Double, b: Double): Double {
        val d = GeoMath.clampBearing(a - b)
        return min(d, 360.0 - d)
    }

    // --- Multi-observer pairwise intervisibility matrix ---

    fun multiObserverMatrix(
        observers: List<GeoPoint>,
        elevations: ElevationGrid,
        eyeHeightM: Double = 1.7,
        samples: Int = 60,
        useCurvature: Boolean = true,
        refraction: Double = GeoMath.DEFAULT_REFRACTION,
    ): List<List<Boolean>> {
        val n = observers.size
        val matrix = List(n) { MutableList(n) { false } }
        for (i in 0 until n) {
            matrix[i][i] = true
            for (j in i + 1 until n) {
                val v = intervisibility(
                    observers[i], observers[j], elevations,
                    eyeHeightM, eyeHeightM, samples, useCurvature, refraction
                ).visible
                matrix[i][j] = v
                matrix[j][i] = v
            }
        }
        return matrix
    }

    fun matrixSummary(matrix: List<List<Boolean>>): String {
        if (matrix.isEmpty()) return "No observers"
        val n = matrix.size
        var links = 0
        for (i in 0 until n) for (j in i + 1 until n) if (matrix[i][j]) links++
        val maxLinks = n * (n - 1) / 2
        return "$links / $maxLinks pairs intervisible"
    }
}
