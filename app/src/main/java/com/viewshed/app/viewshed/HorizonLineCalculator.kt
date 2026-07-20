package com.viewshed.app.viewshed

/**
 * Outer visible extent for map framing only.
 * Use [ViewshedResult.visibleSectors] when displaying or exporting actual visibility.
 */
object HorizonLineCalculator {

    suspend fun calculate(
        observer: GeoPoint,
        elevations: ElevationGrid,
        maxDistKm: Double = 10.0,
        quality: SampleQuality = SampleQuality.HIGH
    ): List<GeoPoint> {
        val params = ViewshedParams(
            maxDistKm = maxDistKm,
            quality = quality,
            adaptiveSampling = false,
            binarySearchHorizon = false,
            parallelRays = true,
            useDemoTerrain = elevations.useDemo
        ).withQuality(quality)
        val result = ViewshedEngine.compute(observer, params, elevations)
        val ring = result.boundary
        return if (ring.size > 1 && ring.first() == ring.last()) {
            ring.dropLast(1)
        } else {
            ring
        }
    }
}
