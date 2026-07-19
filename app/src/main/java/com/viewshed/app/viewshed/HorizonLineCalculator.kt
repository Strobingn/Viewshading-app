package com.viewshed.app.viewshed

// Horizon line calculation for visual horizon display
object HorizonLineCalculator {
    fun calculateHorizonLine(observer: GeoPoint, rays: Int = 360, maxDistanceM: Double = 20000.0): List<GeoPoint> {
        // For each bearing, find farthest visible point (reuse ViewshedEngine logic)
        // Return list of horizon points for drawing as polyline on map
        return emptyList() // Scaffold - implement using existing engine
    }
}
