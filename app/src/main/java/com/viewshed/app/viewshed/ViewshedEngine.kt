package com.viewshed.app.viewshed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

/**
 * Radial reference-plane viewshed inspired by Wang et al.'s incremental horizon method.
 * Each ray maintains the maximum terrain gradient encountered so terrain shadows remain
 * accurate while distant peaks can become visible again above an intervening ridge.
 */
object ViewshedEngine {
    private const val ANGLE_EPSILON = 1e-12

    suspend fun compute(
        observer: GeoPoint,
        params: ViewshedParams,
        elevations: ElevationGrid,
        onRayProgress: (suspend (done: Int, total: Int) -> Unit)? = null
    ): ViewshedResult = withContext(Dispatchers.Default) {
        val p = params.sanitized()
        val observerGround = elevations.elevation(observer)
        val eyeElevation = observerGround + p.eyeHeightM
        val maxDistanceM = p.maxDistKm * 1000.0
        val concurrency = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val semaphore = Semaphore(concurrency)

        val rays = if (p.parallelRays && p.numRays >= 16) {
            coroutineScope {
                (0 until p.numRays).map { rayIndex ->
                    async {
                        semaphore.withPermit {
                            sampleRay(observer, rayIndex * 360.0 / p.numRays, eyeElevation, maxDistanceM, p, elevations)
                        }
                    }
                }.awaitAll()
            }.also { onRayProgress?.invoke(p.numRays, p.numRays) }
        } else {
            buildList(p.numRays) {
                for (rayIndex in 0 until p.numRays) {
                    add(sampleRay(observer, rayIndex * 360.0 / p.numRays, eyeElevation, maxDistanceM, p, elevations))
                    onRayProgress?.invoke(rayIndex + 1, p.numRays)
                }
            }
        }

        val ranges = rays.map { it.farthestVisibleM }
        val boundary = buildOuterExtent(observer, rays)
        val sectors = buildVisibleSectors(observer, rays, p)
        val allSamples = rays.flatMap { it.samples }
        val visibleCells = allSamples.count { it.visible }
        val positiveRanges = ranges.filter { it > 0.0 }
        val horizon = rays.mapNotNull { ray ->
            val point = ray.horizonPoint ?: return@mapNotNull null
            val sample = ray.samples.maxByOrNull { it.terrainAngleRad } ?: return@mapNotNull null
            HorizonPoint(ray.bearingDeg, point, sample.distanceM, sample.terrainElevationM, ray.horizonAngleRad)
        }

        ViewshedResult(
            observer = observer,
            boundary = boundary,
            rangesM = ranges,
            stats = ViewshedStats(
                boundaryPoints = boundary.size,
                maxRangeM = ranges.maxOrNull() ?: 0.0,
                avgRangeM = positiveRanges.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                areaKm2 = sectors.sumOf { it.areaM2 } / 1_000_000.0,
                numRays = p.numRays,
                samplesPerRay = if (rays.isEmpty()) 0 else allSamples.size / rays.size,
                visibleCells = visibleCells,
                totalCells = allSamples.size,
                averageTerrainElevationM = allSamples.map { it.terrainElevationM }.average()
            ),
            params = p,
            visibilityRays = rays,
            visibleSectors = sectors,
            horizonLine = horizon
        )
    }

    private fun sampleRay(
        observer: GeoPoint,
        bearing: Double,
        eyeElevation: Double,
        maxDistanceM: Double,
        params: ViewshedParams,
        elevations: ElevationGrid
    ): VisibilityRay {
        val baseStep = maxDistanceM / params.samplesPerRay
        val distances = mutableListOf<Double>()
        for (i in 1..params.samplesPerRay) distances += i * baseStep
        if (params.adaptiveSampling && params.maxAdaptiveDepth > 0) {
            refineDistances(observer, bearing, elevations, 0.0, maxDistanceM, params, 0, distances)
        }

        val ordered = distances.distinct().sorted()
        val samples = ArrayList<VisibilitySample>(ordered.size)
        var maxTerrainAngle = Double.NEGATIVE_INFINITY
        var farthestVisibleM = 0.0
        var horizonPoint: GeoPoint? = null

        ordered.forEach { distanceM ->
            val point = GeoMath.destination(observer, bearing, distanceM)
            val ground = elevations.elevation(point)
            val terrainAngle = GeoMath.elevationAngleRad(eyeElevation, ground, distanceM, params.useCurvature, params.refraction)
            val targetAngle = GeoMath.elevationAngleRad(eyeElevation, ground + params.targetHeightM, distanceM, params.useCurvature, params.refraction)
            val visible = targetAngle >= maxTerrainAngle - ANGLE_EPSILON
            if (visible) farthestVisibleM = distanceM
            samples += VisibilitySample(point, distanceM, ground, terrainAngle, targetAngle, visible, maxTerrainAngle)
            if (terrainAngle > maxTerrainAngle) {
                maxTerrainAngle = terrainAngle
                horizonPoint = point
            }
        }
        return VisibilityRay(bearing, samples, farthestVisibleM, horizonPoint, maxTerrainAngle)
    }

    private fun refineDistances(
        observer: GeoPoint,
        bearing: Double,
        elevations: ElevationGrid,
        startM: Double,
        endM: Double,
        params: ViewshedParams,
        depth: Int,
        output: MutableList<Double>
    ) {
        if (depth >= params.maxAdaptiveDepth || endM - startM < 2.0) return
        val mid = (startM + endM) / 2.0
        val e0 = elevations.elevation(GeoMath.destination(observer, bearing, max(startM, 0.01)))
        val em = elevations.elevation(GeoMath.destination(observer, bearing, mid))
        val e1 = elevations.elevation(GeoMath.destination(observer, bearing, endM))
        val interpolated = (e0 + e1) / 2.0
        if (abs(em - interpolated) >= params.terrainComplexityThresholdM) {
            output += mid
            refineDistances(observer, bearing, elevations, startM, mid, params, depth + 1, output)
            refineDistances(observer, bearing, elevations, mid, endM, params, depth + 1, output)
        }
    }

    private fun buildOuterExtent(observer: GeoPoint, rays: List<VisibilityRay>): List<GeoPoint> =
        rays.map { if (it.farthestVisibleM > 0.0) GeoMath.destination(observer, it.bearingDeg, it.farthestVisibleM) else observer }
            .let { if (it.isEmpty()) it else it + it.first() }

    private fun buildVisibleSectors(observer: GeoPoint, rays: List<VisibilityRay>, params: ViewshedParams): List<VisibleSector> {
        val sectors = mutableListOf<VisibleSector>()
        val width = 360.0 / params.numRays
        rays.forEach { ray ->
            var start = -1
            for (i in 0..ray.samples.size) {
                val visible = i < ray.samples.size && ray.samples[i].visible
                if (visible && start < 0) start = i
                if (!visible && start >= 0) {
                    val inner = if (start == 0) 0.0 else ray.samples[start - 1].distanceM
                    val outer = ray.samples[i - 1].distanceM
                    sectors += VisibleSector(
                        GeoMath.clampBearing(ray.bearingDeg - width / 2.0),
                        GeoMath.clampBearing(ray.bearingDeg + width / 2.0),
                        inner,
                        outer,
                        i - start,
                        GeoMath.annularSectorAreaM2(inner, outer, width),
                        sectorBoundary(observer, ray.bearingDeg - width / 2.0, ray.bearingDeg + width / 2.0, inner, outer)
                    )
                    start = -1
                }
            }
        }
        return sectors
    }

    private fun sectorBoundary(observer: GeoPoint, start: Double, end: Double, inner: Double, outer: Double): List<GeoPoint> {
        val outerStart = GeoMath.destination(observer, start, outer)
        val outerEnd = GeoMath.destination(observer, end, outer)
        if (inner <= 0.0) return listOf(observer, outerStart, outerEnd, observer)
        val innerStart = GeoMath.destination(observer, start, inner)
        val innerEnd = GeoMath.destination(observer, end, inner)
        return listOf(innerStart, outerStart, outerEnd, innerEnd, innerStart)
    }

    fun samplePoints(observer: GeoPoint, params: ViewshedParams): List<GeoPoint> {
        val p = params.sanitized()
        val maxDistanceM = p.maxDistKm * 1000.0
        return buildList(p.numRays * p.samplesPerRay + 1) {
            add(observer)
            for (rayIndex in 0 until p.numRays) {
                val bearing = rayIndex * 360.0 / p.numRays
                for (sampleIndex in 1..p.samplesPerRay) {
                    add(GeoMath.destination(observer, bearing, sampleIndex * maxDistanceM / p.samplesPerRay))
                }
            }
        }
    }
}
