package com.viewshed.app.viewshed

/**
 * Elevation profile along a ray or line.
 */
object ElevationProfileTool {

    suspend fun getProfile(
        start: GeoPoint,
        end: GeoPoint,
        steps: Int = 100
    ): List<Pair<Double, Double>> { // distance, elevation
        val profile = mutableListOf<Pair<Double, Double>>()
        val totalDist = GeoMath.distanceMeters(start, end)
        val stepDist = totalDist / steps

        for (i in 0..steps) {
            val d = i * stepDist
            val p = GeoMath.destinationPoint(start, GeoMath.bearing(start, end), d)
            val elev = ElevationRepository.getElevation(p)
            profile.add(d to elev)
        }
        return profile
    }
}
