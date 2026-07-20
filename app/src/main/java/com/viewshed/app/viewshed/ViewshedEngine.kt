package com.viewshed.app.viewshed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Fixed-grid radial viewshed using a terrain-horizon test for every sample cell.
 *
 * Terrain angles update the obstruction horizon. Target height is applied only to the
 * candidate target, never to intervening terrain. Every visible/invisible cell is retained,
 * so a visible peak beyond a hidden valley is represented without filling the valley.
 */
object ViewshedEngine {

    private const val ANGLE_EPSILON = 1e-12

    suspend fun compute(
        observer: GeoPoint,
        params: ViewshedParams,
        elevations: ElevationGrid,
        onRayProgress: (suspend (done: Int, total: Int) -> Unit)? = null
    ): ViewshedResult = withContext(Dispatchers.Default) {
        val sanitizedParams = params.sanitized()
        val maxDistanceM = sanitizedParams.maxDistKm * 1000.0
        val observerGround = elevations.elevation(observer)
        val eyeElevation = observerGround + sanitizedParams.eyeHeightM

        val rays = if (sanitizedParams.parallelRays && sanitizedParams.numRays >= 16) {
            coroutineScope {
                (0 until sanitizedParams.numRays).map { rayIndex ->
                    async {
                        val bearing = rayIndex * 360.0 / sanitizedParams.numRays
                        sampleRay(
                            observer = observer,
                            bearing = bearing,
                            eyeElevation = eyeElevation,
                            maxDistanceM = maxDistanceM,
                            params = sanitizedParams,
                            elevations = elevations
                        )
                    }
                }.awaitAll()
            }.also {
                onRayProgress?.invoke(sanitizedParams.numRays, sanitizedParams.numRays)
            }
        } else {
            val output = ArrayList<VisibilityRay>(sanitizedParams.numRays)
            for (rayIndex in 0 until sanitizedParams.numRays) {
                val bearing = rayIndex * 360.0 / sanitizedParams.numRays
                output.add(
                    sampleRay(
                        observer = observer,
                        bearing = bearing,
                        eyeElevation = eyeElevation,
                        maxDistanceM = maxDistanceM,
                        params = sanitizedParams,
                        elevations = elevations
                    )
                )
                onRayProgress?.invoke(rayIndex + 1, sanitizedParams.numRays)
            }
            output
        }

        val ranges = rays.map { it.farthestVisibleM }
        val boundary = buildOuterExtent(observer, rays)
        val sectors = buildVisibleSectors(observer, rays, sanitizedParams)
        val positiveRanges = ranges.filter { it > 0.0 }
        val visibleCells = sectors.sumOf { it.visibleCellCount }
        val stats = ViewshedStats(
            boundaryPoints = boundary.size,
            maxRangeM = ranges.maxOrNull() ?: 0.0,
            avgRangeM = positiveRanges.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            areaKm2 = sectors.sumOf { it.areaM2 } / 1_000_000.0,
            numRays = sanitizedParams.numRays,
            samplesPerRay = sanitizedParams.samplesPerRay,
            visibleCells = visibleCells,
            totalCells = sanitizedParams.numRays * sanitizedParams.samplesPerRay
        )

        ViewshedResult(
            observer = observer,
            boundary = boundary,
            rangesM = ranges,
            stats = stats,
            params = sanitizedParams,
            visibilityRays = rays,
            visibleSectors = sectors
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
        val samples = ArrayList<VisibilitySample>(params.samplesPerRay)
        val stepM = maxDistanceM / params.samplesPerRay
        var maxTerrainAngle = Double.NEGATIVE_INFINITY
        var farthestVisibleM = 0.0

        for (sampleIndex in 1..params.samplesPerRay) {
            val distanceM = sampleIndex * stepM
            val point = GeoMath.destination(observer, bearing, distanceM)
            val groundElevation = elevations.elevation(point)
            val terrainAngle = GeoMath.elevationAngleRad(
                observerElev = eyeElevation,
                targetElev = groundElevation,
                distM = distanceM,
                useCurvature = params.useCurvature,
                refractionCoeff = params.refraction
            )
            val targetAngle = GeoMath.elevationAngleRad(
                observerElev = eyeElevation,
                targetElev = groundElevation + params.targetHeightM,
                distM = distanceM,
                useCurvature = params.useCurvature,
                refractionCoeff = params.refraction
            )

            val visible = targetAngle >= maxTerrainAngle - ANGLE_EPSILON
            if (visible) {
                farthestVisibleM = distanceM
            }

            samples.add(
                VisibilitySample(
                    point = point,
                    distanceM = distanceM,
                    terrainElevationM = groundElevation,
                    terrainAngleRad = terrainAngle,
                    targetAngleRad = targetAngle,
                    visible = visible
                )
            )

            // Only physical terrain becomes an obstruction for later targets.
            if (terrainAngle > maxTerrainAngle) {
                maxTerrainAngle = terrainAngle
            }
        }

        return VisibilityRay(
            bearingDeg = bearing,
            samples = samples,
            farthestVisibleM = farthestVisibleM
        )
    }

    private fun buildOuterExtent(
        observer: GeoPoint,
        rays: List<VisibilityRay>
    ): List<GeoPoint> {
        val boundary = ArrayList<GeoPoint>(rays.size + 1)
        rays.forEach { ray ->
            boundary.add(
                if (ray.farthestVisibleM > 0.0) {
                    GeoMath.destination(observer, ray.bearingDeg, ray.farthestVisibleM)
                } else {
                    observer
                }
            )
        }
        if (boundary.isNotEmpty()) {
            boundary.add(boundary.first())
        }
        return boundary
    }

    private fun buildVisibleSectors(
        observer: GeoPoint,
        rays: List<VisibilityRay>,
        params: ViewshedParams
    ): List<VisibleSector> {
        val sectors = mutableListOf<VisibleSector>()
        val maxDistanceM = params.maxDistKm * 1000.0
        val radialStepM = maxDistanceM / params.samplesPerRay
        val bearingWidthDeg = 360.0 / params.numRays
        val halfBearingWidth = bearingWidthDeg / 2.0

        rays.forEach { ray ->
            var runStart = -1
            for (index in 0..ray.samples.size) {
                val isVisible = index < ray.samples.size && ray.samples[index].visible
                if (isVisible && runStart < 0) {
                    runStart = index
                }
                if (!isVisible && runStart >= 0) {
                    val innerDistanceM = runStart * radialStepM
                    val outerDistanceM = index * radialStepM
                    val bearingStart = GeoMath.clampBearing(
                        ray.bearingDeg - halfBearingWidth
                    )
                    val bearingEnd = GeoMath.clampBearing(
                        ray.bearingDeg + halfBearingWidth
                    )
                    sectors.add(
                        VisibleSector(
                            bearingStartDeg = bearingStart,
                            bearingEndDeg = bearingEnd,
                            innerDistanceM = innerDistanceM,
                            outerDistanceM = outerDistanceM,
                            visibleCellCount = index - runStart,
                            areaM2 = GeoMath.annularSectorAreaM2(
                                innerDistanceM = innerDistanceM,
                                outerDistanceM = outerDistanceM,
                                bearingWidthDeg = bearingWidthDeg
                            ),
                            boundary = sectorBoundary(
                                observer = observer,
                                bearingStartDeg = bearingStart,
                                bearingEndDeg = bearingEnd,
                                innerDistanceM = innerDistanceM,
                                outerDistanceM = outerDistanceM
                            )
                        )
                    )
                    runStart = -1
                }
            }
        }
        return sectors
    }

    private fun sectorBoundary(
        observer: GeoPoint,
        bearingStartDeg: Double,
        bearingEndDeg: Double,
        innerDistanceM: Double,
        outerDistanceM: Double
    ): List<GeoPoint> {
        val outerStart = GeoMath.destination(observer, bearingStartDeg, outerDistanceM)
        val outerEnd = GeoMath.destination(observer, bearingEndDeg, outerDistanceM)
        if (innerDistanceM <= 0.0) {
            return listOf(observer, outerStart, outerEnd, observer)
        }

        val innerStart = GeoMath.destination(observer, bearingStartDeg, innerDistanceM)
        val innerEnd = GeoMath.destination(observer, bearingEndDeg, innerDistanceM)
        return listOf(innerStart, outerStart, outerEnd, innerEnd, innerStart)
    }

    /** Every point required by [compute]; real-mode calculations must resolve all of them. */
    fun samplePoints(observer: GeoPoint, params: ViewshedParams): List<GeoPoint> {
        val sanitizedParams = params.sanitized()
        val maxDistanceM = sanitizedParams.maxDistKm * 1000.0
        val output = ArrayList<GeoPoint>(
            sanitizedParams.numRays * sanitizedParams.samplesPerRay + 1
        )
        output.add(observer)
        for (rayIndex in 0 until sanitizedParams.numRays) {
            val bearing = rayIndex * 360.0 / sanitizedParams.numRays
            for (sampleIndex in 1..sanitizedParams.samplesPerRay) {
                val distanceM = sampleIndex * maxDistanceM /
                    sanitizedParams.samplesPerRay
                output.add(GeoMath.destination(observer, bearing, distanceM))
            }
        }
        return output
    }
}
