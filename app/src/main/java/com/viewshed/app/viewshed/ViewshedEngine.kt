package com.viewshed.app.viewshed

import kotlinx.coroutines.*
import kotlin.math.*

object ViewshedEngine {

    enum class Quality { LOW, MED, HIGH }

    data class ViewshedResult(
        val visiblePoints: List<GeoPoint>,
        val observer: GeoPoint,
        val maxDistanceM: Double,
        val raysUsed: Int,
        val samplesPerRay: Int
    )

    suspend fun computeViewshed(
        observer: GeoPoint,
        eyeHeightM: Double = 1.5,
        maxDistanceM: Double = 5000.0,
        quality: Quality = Quality.MED,
        useBinarySearch: Boolean = false,
        useAdaptiveSampling: Boolean = true
    ): ViewshedResult = withContext(Dispatchers.Default) {

        val (numRays, baseSamples) = when (quality) {
            Quality.LOW  -> 36 to 20
            Quality.MED  -> 72 to 30
            Quality.HIGH -> 120 to 50
        }

        val terrainAtObserver = ElevationRepository.getElevation(observer)
        val observerHeight = terrainAtObserver + eyeHeightM

        val visiblePoints = mutableListOf<GeoPoint>()
        val jobs = mutableListOf<Deferred<List<GeoPoint>>>()
        val angleStep = 360.0 / numRays

        for (r in 0 until numRays) {
            val bearing = r * angleStep
            jobs += async {
                when {
                    useBinarySearch -> binarySearchHorizon(observer, bearing, observerHeight, maxDistanceM, baseSamples)
                    useAdaptiveSampling -> adaptiveSampleRay(observer, bearing, observerHeight, maxDistanceM, baseSamples)
                    else -> linearSampleRay(observer, bearing, observerHeight, maxDistanceM, baseSamples)
                }
            }
        }

        jobs.awaitAll().forEach { visiblePoints.addAll(it) }

        val simplified = if (visiblePoints.isNotEmpty()) {
            (visiblePoints + visiblePoints.first()).distinct()
        } else emptyList()

        ViewshedResult(visiblePoints = simplified, observer = observer, maxDistanceM = maxDistanceM, raysUsed = numRays, samplesPerRay = baseSamples)
    }

    private suspend fun linearSampleRay(
        observer: GeoPoint,
        bearing: Double,
        observerHeight: Double,
        maxDist: Double,
        samples: Int
    ): List<GeoPoint> {
        val step = maxDist / samples
        var maxSlope = Double.NEGATIVE_INFINITY
        val visible = mutableListOf<GeoPoint>()

        for (s in 1..samples) {
            val d = s * step
            val p = GeoMath.destinationPoint(observer, bearing, d)
            val h = ElevationRepository.getElevation(p)
            val slope = atan((h - observerHeight) / d.coerceAtLeast(1.0))

            if (slope > maxSlope) {
                maxSlope = slope
                visible += p
            } else if (slope < maxSlope - 0.01 && d > maxDist * 0.2) {
                break
            }
        }
        return visible
    }

    private suspend fun binarySearchHorizon(
        observer: GeoPoint,
        bearing: Double,
        observerHeight: Double,
        maxDist: Double,
        samples: Int
    ): List<GeoPoint> {
        var low = 0.0
        var high = maxDist
        var best: GeoPoint? = null

        repeat(12) {
            val mid = (low + high) / 2
            val p = GeoMath.destinationPoint(observer, bearing, mid)
            val h = ElevationRepository.getElevation(p)
            val slope = atan((h - observerHeight) / mid.coerceAtLeast(1.0))

            if (slope > 0) {
                best = p
                low = mid
            } else {
                high = mid
            }
        }
        return if (best != null) listOf(best) else emptyList()
    }

    private suspend fun adaptiveSampleRay(
        observer: GeoPoint,
        bearing: Double,
        observerHeight: Double,
        maxDist: Double,
        baseSamples: Int
    ): List<GeoPoint> {
        val visible = mutableListOf<GeoPoint>()
        var d = 0.0
        var step = maxDist / baseSamples
        var prevSlope = Double.NEGATIVE_INFINITY
        var maxSlope = Double.NEGATIVE_INFINITY

        while (d < maxDist) {
            val p = GeoMath.destinationPoint(observer, bearing, d)
            val h = ElevationRepository.getElevation(p)
            val slope = atan((h - observerHeight) / d.coerceAtLeast(1.0))

            if (slope > maxSlope) {
                maxSlope = slope
                visible += p
            }

            val slopeDelta = abs(slope - prevSlope)
            step = when {
                slopeDelta > 0.05 -> step * 0.6
                slopeDelta < 0.01 -> step * 1.4
                else -> step
            }.coerceIn(maxDist / 200, maxDist / 8)

            prevSlope = slope
            d += step

            if (slope < maxSlope - 0.02 && d > maxDist * 0.3) break
        }
        return visible
    }

    suspend fun vulkanComputeViewshed(
        observer: GeoPoint,
        eyeHeightM: Double = 1.5,
        maxDistanceM: Double = 5000.0,
        numRays: Int = 72
    ): ViewshedResult = withContext(Dispatchers.Default) {
        // Falls back to adaptive for now; replace with real Vulkan dispatch when native integration is complete
        adaptiveSampleRay(observer, 0.0, eyeHeightM + ElevationRepository.getElevation(observer), maxDistanceM, numRays * 30).let {
            ViewshedResult(it, observer, maxDistanceM, numRays, numRays * 30)
        }
    }
}
