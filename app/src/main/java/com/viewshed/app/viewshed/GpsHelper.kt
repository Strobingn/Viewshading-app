package com.viewshed.app.viewshed

/**
 * GPS helper for automatic observer placement.
 */
object GpsHelper {
    fun getCurrentLocationAsObserver(): GeoPoint? {
        // TODO: integrate with Android LocationManager / FusedLocationProvider
        // For now returns null - implement with permission handling
        return null
    }

    fun autoPlaceObserverOnMap(currentGps: GeoPoint?): GeoPoint {
        return currentGps ?: GeoPoint(0.0, 0.0) // fallback
    }
}
