package com.viewshed.app.viewshed

/**
 * Horizon ring for map display — outer boundary of a high-resolution viewshed.
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
            adaptiveSampling = true,
            binarySearchHorizon = true,
            parallelRays = true,
            useDemoTerrain = elevations.useDemo
        ).withQuality(quality)
        val result = ViewshedEngine.compute(observer, params, elevations)
        val ring = result.boundary
        return if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
    }
}
