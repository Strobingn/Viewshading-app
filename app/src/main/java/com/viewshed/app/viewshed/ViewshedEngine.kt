package com.viewshed.app.viewshed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Radial line-of-sight viewshed (max elevation-angle, first continuous occlusion).
 */
object ViewshedEngine {

    private const val ANGLE_EPS = 1e-10

    suspend fun compute(
        observer: GeoPoint,
        params: ViewshedParams,
        elevations: ElevationGrid,
        onRayProgress: (suspend (done: Int, total: Int) -> Unit)? = null
    ): ViewshedResult = withContext(Dispatchers.Default) {
        val p = params.sanitized()
        val maxDistM = p.maxDistKm * 1000.0
        val observerGround = elevations.elevation(observer)
        val eyeElev = observerGround + p.eyeHeightM

        val ranges: List<Double> = if (p.parallelRays && p.numRays >= 16) {
            coroutineScope {
                (0 until p.numRays).map { i ->
                    async {
                        val bearing = i * 360.0 / p.numRays
                        sampleRay(observer, bearing, eyeElev, maxDistM, p, elevations)
                    }
                }.awaitAll()
            }.also {
                onRayProgress?.invoke(p.numRays, p.numRays)
            }
        } else {
            val out = ArrayList<Double>(p.numRays)
            for (i in 0 until p.numRays) {
                val bearing = i * 360.0 / p.numRays
                out.add(sampleRay(observer, bearing, eyeElev, maxDistM, p, elevations))
                onRayProgress?.invoke(i + 1, p.numRays)
            }
            out
        }

        val boundary = ArrayList<GeoPoint>(p.numRays + 1)
        for (i in 0 until p.numRays) {
            val bearing = i * 360.0 / p.numRays
            val dist = ranges[i]
            if (dist > 1.0) {
                boundary.add(GeoMath.destination(observer, bearing, dist))
            } else {
                // Tiny offset so polygon is not degenerate when range ~ 0
                boundary.add(GeoMath.destination(observer, bearing, 1.0))
            }
        }
        if (boundary.isNotEmpty()) boundary.add(boundary.first())

        val positive = ranges.filter { it > 0.0 }
        val stats = ViewshedStats(
            boundaryPoints = boundary.size,
            maxRangeM = ranges.maxOrNull() ?: 0.0,
            avgRangeM = if (positive.isEmpty()) 0.0 else positive.average(),
            areaKm2 = GeoMath.polygonAreaKm2(boundary),
            numRays = p.numRays,
            samplesPerRay = p.samplesPerRay
        )

        ViewshedResult(
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
        elevations: ElevationGrid
    ): Double {
        val raw = if (p.adaptiveSampling) {
            adaptiveSampleRay(observer, bearing, eyeElev, maxDistM, p, elevations)
        } else {
            linearSampleRay(observer, bearing, eyeElev, maxDistM, p, elevations)
        }
        val hidden = raw.firstHidden
        return if (p.binarySearchHorizon && raw.lastVisible > 0.0 && hidden != null) {
            refineFirstOcclusion(
                observer, bearing, eyeElev, raw.lastVisible, hidden, p, elevations
            )
        } else {
            raw.lastVisible
        }
    }

    private data class RayHit(val lastVisible: Double, val firstHidden: Double?)

    private fun linearSampleRay(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: ElevationGrid
    ): RayHit {
        var horizonAngle = Double.NEGATIVE_INFINITY
        var lastVisible = 0.0
        val samples = p.samplesPerRay

        for (s in 1..samples) {
            val distM = s * maxDistM / samples
            val angle = elevAngle(observer, bearing, distM, eyeElev, p, elevations)
            if (angle > horizonAngle + ANGLE_EPS) {
                horizonAngle = angle
                lastVisible = distM
            } else {
                return RayHit(lastVisible, distM)
            }
        }
        return RayHit(lastVisible, null)
    }

    /**
     * Adaptive steps only on the same distance lattice as [samplePoints]
     * (integer sample indices) so elevation pre-fetch keys always hit.
     */
    private fun adaptiveSampleRay(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: ElevationGrid
    ): RayHit {
        var horizonAngle = Double.NEGATIVE_INFINITY
        var lastVisible = 0.0
        val samples = p.samplesPerRay
        var s = 1
        var stride = 1
        var prevAngle = Double.NaN

        while (s <= samples) {
            val distM = s * maxDistM / samples
            val angle = elevAngle(observer, bearing, distM, eyeElev, p, elevations)
            if (angle > horizonAngle + ANGLE_EPS) {
                horizonAngle = angle
                lastVisible = distM
                if (!prevAngle.isNaN()) {
                    val delta = kotlin.math.abs(angle - prevAngle)
                    stride = when {
                        delta > 0.008 -> 1
                        delta < 0.0015 -> min(4, stride + 1)
                        else -> stride
                    }
                }
                prevAngle = angle
                s += stride
            } else {
                return RayHit(lastVisible, distM)
            }
        }
        return RayHit(lastVisible, null)
    }

    private fun refineFirstOcclusion(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        lastVisible: Double,
        firstHidden: Double,
        p: ViewshedParams,
        elevations: ElevationGrid
    ): Double {
        if (firstHidden <= lastVisible) return lastVisible

        var horizonAngle = Double.NEGATIVE_INFINITY
        val seedSteps = max(4, p.samplesPerRay / 5)
        for (s in 1..seedSteps) {
            val d = s * lastVisible / seedSteps
            if (d <= 0.0) continue
            val a = elevAngle(observer, bearing, d, eyeElev, p, elevations)
            if (a > horizonAngle + ANGLE_EPS) horizonAngle = a
        }

        var lo = lastVisible
        var hi = firstHidden
        repeat(14) {
            val mid = (lo + hi) / 2.0
            val a = elevAngle(observer, bearing, mid, eyeElev, p, elevations)
            if (a > horizonAngle + ANGLE_EPS) {
                lo = mid
                horizonAngle = a
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
        elevations: ElevationGrid
    ): Double {
        val target = GeoMath.destination(observer, bearing, distM)
        val ground = elevations.elevation(target)
        val targetElev = ground + p.targetHeightM
        return GeoMath.elevationAngleRad(
            observerElev = eyeElev,
            targetElev = targetElev,
            distM = distM,
            useCurvature = p.useCurvature,
            refractionCoeff = p.refraction
        )
    }

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
