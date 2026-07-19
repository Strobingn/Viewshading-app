package com.viewshed.app.viewshed

/**
 * Calculates horizon line points for visual display on map.
 * Reuses ViewshedEngine logic for accuracy.
 */
object HorizonLineCalculator {

    suspend fun calculateHorizonLine(
        observer: GeoPoint,
        rays: Int = 360,
        maxDistanceM: Double = 20000.0
    ): List<GeoPoint> {
        val result = ViewshedEngine.computeViewshed(
            observer = observer,
            maxDistanceM = maxDistanceM,
            quality = ViewshedEngine.Quality.MED,
            useAdaptiveSampling = true
        )
        // Take the outer visible points as horizon approximation
        return result.visiblePoints.take(rays)
    }
}
