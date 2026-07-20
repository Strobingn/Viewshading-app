package com.viewshed.app.viewshed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min

/**
 * Radial line-of-sight viewshed using the classic **max elevation-angle** method.
 *
 * Along each ray (near → far):
 * 1. Compute elevation angle from the eye to the terrain sample.
 * 2. If the angle is **strictly above** the running horizon angle → sample is visible;
 *    update the horizon.
 * 3. Otherwise the sample is occluded. For a continuous radial lobe we **stop** at the
 *    first occlusion (do not jump to a farther peak and fill the hidden valley).
 *
 * That continuous first-occlusion boundary is what the green polygon represents.
 */
object ViewshedEngine {

    /** Small epsilon (radians) so floating noise does not flicker visibility. */
    private const val ANGLE_EPS = 1e-10

    fun compute(
        observer: GeoPoint,
        params: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double = { DemoTerrain.elevation(it) },
        onRayProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ViewshedResult {
        val p = params.sanitized()
        val maxDistM = p.maxDistKm * 1000.0
        val observerGround = elevAt(observer, elevations, demoElev)
        val eyeElev = observerGround + p.eyeHeightM

        val ranges = if (p.parallelRays && p.numRays >= 16) {
            runBlocking(Dispatchers.Default) {
                (0 until p.numRays).map { i ->
                    async {
                        val bearing = i * 360.0 / p.numRays
                        sampleRay(observer, bearing, eyeElev, maxDistM, p, elevations, demoElev)
                    }
                }.awaitAll()
            }
        } else {
            val out = ArrayList<Double>(p.numRays)
            for (i in 0 until p.numRays) {
                val bearing = i * 360.0 / p.numRays
                out.add(sampleRay(observer, bearing, eyeElev, maxDistM, p, elevations, demoElev))
                onRayProgress?.invoke(i + 1, p.numRays)
            }
            out
        }

        if (p.parallelRays) {
            onRayProgress?.invoke(p.numRays, p.numRays)
        }

        val boundary = ArrayList<GeoPoint>(p.numRays + 1)
        for (i in 0 until p.numRays) {
            val bearing = i * 360.0 / p.numRays
            val dist = ranges[i]
            if (dist > 0.0) {
                boundary.add(GeoMath.destination(observer, bearing, dist))
            } else {
                // Fully blocked / no range — pin at observer so polygon stays simple
                boundary.add(observer)
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
        val raw = if (p.adaptiveSampling) {
            adaptiveSampleRay(observer, bearing, eyeElev, maxDistM, p, elevations, demoElev)
        } else {
            linearSampleRay(observer, bearing, eyeElev, maxDistM, p, elevations, demoElev)
        }
        return if (p.binarySearchHorizon && raw.lastVisible > 0.0 && raw.firstHidden != null) {
            refineFirstOcclusion(
                observer = observer,
                bearing = bearing,
                eyeElev = eyeElev,
                lastVisible = raw.lastVisible,
                firstHidden = raw.firstHidden!!,
                p = p,
                elevations = elevations,
                demoElev = demoElev
            )
        } else {
            raw.lastVisible
        }
    }

    private data class RayHit(
        /** Distance of last visible sample (continuous from observer). */
        val lastVisible: Double,
        /** Distance of first occluded sample, if any (for binary refine). */
        val firstHidden: Double?
    )

    /**
     * Linear samples near→far. Stops continuous visibility at **first** occlusion.
     */
    private fun linearSampleRay(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): RayHit {
        var horizonAngle = Double.NEGATIVE_INFINITY
        var lastVisible = 0.0
        val samples = p.samplesPerRay

        for (s in 1..samples) {
            val distM = s * maxDistM / samples
            val angle = elevAngle(observer, bearing, distM, eyeElev, p, elevations, demoElev)
            // Strictly above current horizon → visible; raises the horizon.
            if (angle > horizonAngle + ANGLE_EPS) {
                horizonAngle = angle
                lastVisible = distM
            } else {
                // First occlusion ends the continuous visible lobe.
                return RayHit(lastVisible = lastVisible, firstHidden = distM)
            }
        }
        return RayHit(lastVisible = lastVisible, firstHidden = null)
    }

    /**
     * Variable step size, but still **stops at first occlusion** (same LOS rule).
     */
    private fun adaptiveSampleRay(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        maxDistM: Double,
        p: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): RayHit {
        var horizonAngle = Double.NEGATIVE_INFINITY
        var lastVisible = 0.0
        var dist = 0.0
        val baseStep = maxDistM / p.samplesPerRay
        val minStep = baseStep * 0.35
        val maxStep = baseStep * 2.5
        var step = baseStep
        var prevAngle = Double.NaN
        var guard = 0

        while (dist < maxDistM - 1e-6 && guard < p.samplesPerRay * 4) {
            guard++
            dist = min(dist + step, maxDistM)
            val angle = elevAngle(observer, bearing, dist, eyeElev, p, elevations, demoElev)
            if (angle > horizonAngle + ANGLE_EPS) {
                horizonAngle = angle
                lastVisible = dist
                if (!prevAngle.isNaN()) {
                    val delta = kotlin.math.abs(angle - prevAngle)
                    step = when {
                        delta > 0.008 -> max(minStep, step * 0.55)
                        delta < 0.0015 -> min(maxStep, step * 1.35)
                        else -> step
                    }
                }
                prevAngle = angle
            } else {
                return RayHit(lastVisible = lastVisible, firstHidden = dist)
            }
        }
        return RayHit(lastVisible = lastVisible, firstHidden = null)
    }

    /**
     * Binary-search the first occlusion between last visible and first hidden sample.
     */
    private fun refineFirstOcclusion(
        observer: GeoPoint,
        bearing: Double,
        eyeElev: Double,
        lastVisible: Double,
        firstHidden: Double,
        p: ViewshedParams,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): Double {
        if (firstHidden <= lastVisible) return lastVisible

        // Horizon angle just before occlusion = angle at lastVisible
        // (recompute along path so horizon matches linear pass).
        var horizonAngle = Double.NEGATIVE_INFINITY
        val seedSteps = max(4, p.samplesPerRay / 5)
        for (s in 1..seedSteps) {
            val d = s * lastVisible / seedSteps
            if (d <= 0.0) continue
            val a = elevAngle(observer, bearing, d, eyeElev, p, elevations, demoElev)
            if (a > horizonAngle + ANGLE_EPS) horizonAngle = a
        }

        var lo = lastVisible
        var hi = firstHidden
        repeat(14) {
            val mid = (lo + hi) / 2.0
            val a = elevAngle(observer, bearing, mid, eyeElev, p, elevations, demoElev)
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
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): Double {
        val target = GeoMath.destination(observer, bearing, distM)
        val ground = elevAt(target, elevations, demoElev)
        val targetElev = ground + p.targetHeightM
        return GeoMath.elevationAngleRad(
            observerElev = eyeElev,
            targetElev = targetElev,
            distM = distM,
            useCurvature = p.useCurvature,
            refractionCoeff = p.refraction
        )
    }

    private fun elevAt(
        point: GeoPoint,
        elevations: Map<String, Double>,
        demoElev: (GeoPoint) -> Double
    ): Double = elevations[point.key()] ?: demoElev(point)

    /** Sample grid for elevation pre-fetch (matches linear sampling lattice). */
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
