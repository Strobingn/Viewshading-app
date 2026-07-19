package com.viewshed.app.viewshed

object MeasurementTool {
    fun calculateDistance(start: GeoPoint, end: GeoPoint): Double {
        return GeoMath.distanceMeters(start, end)
    }

    fun calculateArea(points: List<GeoPoint>): Double {
        // Simple shoelace for polygon area
        if (points.size < 3) return 0.0
        var area = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].longitude * points[j].latitude
            area -= points[j].longitude * points[i].latitude
        }
        return kotlin.math.abs(area) / 2.0
    }
}
