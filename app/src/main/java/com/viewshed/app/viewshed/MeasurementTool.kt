package com.viewshed.app.viewshed

/**
 * Distance / area helpers for field measure mode.
 */
object MeasurementTool {
    fun calculateDistanceM(start: GeoPoint, end: GeoPoint): Double =
        GeoMath.distanceM(start, end)

    fun calculateDistanceKm(start: GeoPoint, end: GeoPoint): Double =
        calculateDistanceM(start, end) / 1000.0

    /** Approximate geodesic polygon area in m². */
    fun calculateAreaM2(points: List<GeoPoint>): Double =
        GeoMath.polygonAreaM2(points)

    fun calculateAreaKm2(points: List<GeoPoint>): Double =
        GeoMath.polygonAreaKm2(points)

    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> String.format(java.util.Locale.US, "%.0f m", meters)
        else -> String.format(java.util.Locale.US, "%.2f km", meters / 1000.0)
    }
}
