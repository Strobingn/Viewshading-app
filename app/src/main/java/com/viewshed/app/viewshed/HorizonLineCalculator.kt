package com.viewshed.app.viewshed

/**
 * Horizon ring for map display — outer boundary of a high-resolution viewshed.
 */
object HorizonLineCalculator {

    fun calculate(
        observer: GeoPoint,
        elevations: Map<String, Double>,
        maxDistKm: Double = 10.0,
        quality: SampleQuality = SampleQuality.HIGH,
        demoElev: (GeoPoint) -> Double = { DemoTerrain.elevation(it) }
    ): List<GeoPoint> {
        val params = ViewshedParams(
            maxDistKm = maxDistKm,
            quality = quality,
            adaptiveSampling = true,
            binarySearchHorizon = true,
            parallelRays = true,
            useDemoTerrain = true
        )
        val result = ViewshedEngine.compute(observer, params, elevations, demoElev)
        // Drop closing duplicate if present
        val ring = result.boundary
        return if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring
    }
}
