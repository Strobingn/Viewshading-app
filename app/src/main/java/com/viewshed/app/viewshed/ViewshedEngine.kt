package com.viewshed.app.viewshed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min

/**
 * Radial line-of-sight viewshed (horizon / max elevation-angle method).
 *
 * Experimental options (all default-safe):
 * - [ViewshedParams.parallelRays] — compute rays in parallel
 * - [ViewshedParams.adaptiveSampling] — denser samples when terrain slope changes
 * - [ViewshedParams.binarySearchHorizon] — refine last visible distance
 */
object ViewshedEngine {

    fun compute(
        observer: GeoPoint,
        params: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double = { DemoTerrain.elevation(it) },
        onRayProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ViewshedResult {
        val p = params.sanitized()
        val maxDistM = p.maxDistKm * 1000.0
        val observerGround = elevations[observer.key()] ?: demoElev(observer)
        val eyeElev = observerGround + p.eyeHeightM

        val ranges = if (p.parallelRays && p.numRays >= 16) {
            runBlocking(Dispatchers.Default) {
                (0 until p.numRays).map { i ->
                    async {
                        val bearing = i * 360.0 / p.numRays
                        sampleRay(
                            observer, bearing, eyeElev, maxDistM, p, elevations, demoElev
                        )
                    }
                }.awaitAll()
            }
        } else {
            val out = ArrayList<Double>(p.numRays)
            for (i in 0 until p.numRays) {
                val bearing = i * 360.0 / p.numRays
                out.add(
                    sampleRay(observer, bearing, eyeElev, maxDistM, p, elevations, demoElev)
                )
                onRayProgress?.invoke(i + 1, p.numRays)
            }
            out
        }

        if (p.parallelRays && onRayProgress != null) {
            onRayProgress(p.numRays, p.numRays)
        }

        val boundary = ArrayList<GeoPoint>(p.numRays + 1)
        for (i in 0 until p.numRays) {
            val bearing = i * 360.0 / p.numRays
            val dist = ranges[i]
            if (dist > 0) {
                boundary.add(GeoMath.destination(observer, bearing, dist))
            } else {
                boundary.add(observer)
            }
        }
        if (boundary.isNotEmpty()) boundary.add(boundary.first())

        val positive = ranges.filter { it > 0 }
        val stats = ViewshedStats(
            boundaryPoints = boundary.size,
            maxRangeM = ranges.maxOrNull() ?: 0.0,
            avgRangeM = if (positive.isEmpty()) 0.0 else positive.average(),
            areaKm2 = GeoMath.polygonAreaKm2(boundary),
            numRays = p.numRays,
            samplesPerRay = p.samplesPerRay
        )

        return ViewshedResult(
            observer = observer,
            boundary = boundary,
            rangesM = ranges,
            stats = stats,
            params = p
        )
    }

    private fun sampleRay(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): Double {
        return if (p.adaptiveSampling) {
            adaptiveSampleRay(observer, bearing, eyeElev, maxDistM, p, elevations, demoElev)
        } else {
            linearSampleRay(observer, bearing, eyeElev, maxDistM, p, elevations, demoElev)
        }
    }

    private fun linearSampleRay(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): Double {
        var maxAngle = Double.NEGATIVE_INFINITY
        var maxVisibleDist = 0.0
        val samples = p.samplesPerRay

        for (s in 1..samples) {
            val distM = s * maxDistM / samples
            val angle = elevAngle(observer, bearing, distM, eyeElev, p, elevations, demoElev)
            if (angle >= maxAngle - 1e-12) {
                maxVisibleDist = distM
            }
            if (angle > maxAngle) maxAngle = angle
        }

        if (p.binarySearchHorizon && maxVisibleDist > 0 && maxVisibleDist < maxDistM) {
            maxVisibleDist = refineHorizon(
                observer, bearing, eyeElev, maxVisibleDist, maxDistM, p, elevations, demoElev
            )
        }
        return maxVisibleDist
    }

    /**
     * Adaptive steps: start coarse, densify when elevation angle changes sharply.
     */
    private fun adaptiveSampleRay(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): Double {
        var maxAngle = Double.NEGATIVE_INFINITY
        var maxVisibleDist = 0.0
        var dist = 0.0
        val baseStep = maxDistM / p.samplesPerRay
        val minStep = baseStep * 0.35
        val maxStep = baseStep * 2.5
        var step = baseStep
        var prevAngle = Double.NaN
        var guard = 0

        while (dist < maxDistM && guard < p.samplesPerRay * 4) {
            guard++
            dist = min(dist + step, maxDistM)
            val angle = elevAngle(observer, bearing, dist, eyeElev, p, elevations, demoElev)
            if (angle >= maxAngle - 1e-12) {
                maxVisibleDist = dist
            }
            if (angle > maxAngle) maxAngle = angle

            if (!prevAngle.isNaN()) {
                val delta = kotlin.math.abs(angle - prevAngle)
                step = when {
                    delta > 0.008 -> max(minStep, step * 0.55)
                    delta < 0.0015 -> min(maxStep, step * 1.35)
                    else -> step
                }
            }
            prevAngle = angle
        }

        if (p.binarySearchHorizon && maxVisibleDist > 0 && maxVisibleDist < maxDistM) {
            maxVisibleDist = refineHorizon(
                observer, bearing, eyeElev, maxVisibleDist, maxDistM, p, elevations, demoElev
            )
        }
        return maxVisibleDist
    }

    /**
     * Binary-search between last known visible distance and max range under fixed horizon angle.
     * Approximates a sharper edge when linear sampling is coarse.
     */
    private fun refineHorizon(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        visibleDist: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): Double {
        // Rebuild horizon angle at visibleDist
        var maxAngle = Double.NEGATIVE_INFINITY
        val steps = max(8, p.samplesPerRay / 4)
        for (s in 1..steps) {
            val d = s * visibleDist / steps
            val a = elevAngle(observer, bearing, d, eyeElev, p, elevations, demoElev)
            if (a > maxAngle) maxAngle = a
        }

        var lo = visibleDist
        var hi = min(maxDistM, visibleDist + (maxDistM / p.samplesPerRay) * 3)
        repeat(12) {
            val mid = (lo + hi) / 2.0
            val a = elevAngle(observer, bearing, mid, eyeElev, p, elevations, demoElev)
            if (a >= maxAngle - 1e-12) {
                lo = mid
                if (a > maxAngle) maxAngle = a
            } else {
                hi = mid
            }
        }
        return lo
    }

    private fun elevAngle(
        observer: GeoPoint,
        bearing: Double,
        distM: Double,
        eyeElev: Double,
        p: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): Double {
        val target = GeoMath.destination(observer, bearing, distM)
        val ground = elevations[target.key()] ?: demoElev(target)
        val targetElev = ground + p.targetHeightM
        return GeoMath.elevationAngleRad(
            observerElev = eyeElev,
            targetElev = targetElev,
            distM = distM,
            useCurvature = p.useCurvature,
            refractionCoeff = p.refraction
        )
    }

    /** All sample points needed for elevation pre-fetch (conservative grid). */
    fun samplePoints(observer: GeoPoint, params: ViewshedParams): List<GeoPoint> {
        val p = params.sanitized()
        val maxDistM = p.maxDistKm * 1000.0
        val out = ArrayList<GeoPoint>(p.numRays * p.samplesPerRay + 1)
        out.add(observer)
        for (i in 0 until p.numRays) {
            val bearing = i * 360.0 / p.numRays
            for (s in 1..p.samplesPerRay) {
                val distM = s * maxDistM / p.samplesPerRay
                out.add(GeoMath.destination(observer, bearing, distM))
            }
        }
        return out
    }
}
