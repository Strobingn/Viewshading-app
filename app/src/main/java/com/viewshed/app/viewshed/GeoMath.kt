package com.viewshed.app.viewshed

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    /** Mean Earth radius in meters. */
    const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle destination from a bearing measured clockwise from north. */
    fun destination(start: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        if (distanceM <= 0.0) return start
        val angularDistance = distanceM / EARTH_RADIUS_M
        val bearing = Math.toRadians(clampBearing(bearingDeg))
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lon)
        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing)
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        return GeoPoint(
            lat = Math.toDegrees(lat2),
            lon = normalizeLongitude(Math.toDegrees(lon2))
        )
    }

    /** Great-circle distance in meters. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(normalizeLongitude(b.lon - a.lon))
        val haversine = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    /** Effective Earth radius for line-of-sight refraction coefficient k. */
    fun effectiveEarthRadiusM(refractionCoeff: Double): Double {
        val k = refractionCoeff.coerceIn(0.0, 0.99)
        return EARTH_RADIUS_M / (1.0 - k)
    }

    /** Approximate smooth-sphere horizon distance for an eye height above ground. */
    fun geometricHorizonM(
        eyeHeightM: Double,
        refractionCoeff: Double = 0.13
    ): Double {
        val height = eyeHeightM.coerceAtLeast(0.0)
        return sqrt(2.0 * effectiveEarthRadiusM(refractionCoeff) * height)
    }

    /**
     * Elevation angle from the observer's local horizontal.
     *
     * Curvature uses the exact effective-Earth-radius spherical geometry rather
     * than only the d²/(2R) short-distance approximation.
     */
    fun elevationAngleRad(
        observerElev: Double,
        targetElev: Double,
        distM: Double,
        useCurvature: Boolean,
        refractionCoeff: Double
    ): Double {
        if (distM <= 0.0) return 0.0
        if (!useCurvature) {
            return atan2(targetElev - observerElev, distM)
        }

        val effectiveRadius = effectiveEarthRadiusM(refractionCoeff)
        val centralAngle = distM / effectiveRadius
        val targetRadius = effectiveRadius + targetElev
        val horizontal = targetRadius * sin(centralAngle)
        val vertical = targetRadius * cos(centralAngle) -
            (effectiveRadius + observerElev)
        return atan2(vertical, horizontal)
    }

    /** Surface area of a spherical annular sector. */
    fun annularSectorAreaM2(
        innerDistanceM: Double,
        outerDistanceM: Double,
        bearingWidthDeg: Double
    ): Double {
        val inner = innerDistanceM.coerceAtLeast(0.0)
        val outer = outerDistanceM.coerceAtLeast(inner)
        val widthRad = abs(degToRad(bearingWidthDeg))
        return widthRad * EARTH_RADIUS_M * EARTH_RADIUS_M *
            (cos(inner / EARTH_RADIUS_M) - cos(outer / EARTH_RADIUS_M))
    }

    /** Spherical polygon area in square meters. Ring may be open or closed. */
    fun polygonAreaM2(ring: List<GeoPoint>): Double {
        if (ring.size < 3) return 0.0
        val points = if (ring.first() == ring.last()) ring.dropLast(1) else ring
        if (points.size < 3) return 0.0

        var area = 0.0
        for (index in points.indices) {
            val first = points[index]
            val second = points[(index + 1) % points.size]
            val lat1 = Math.toRadians(first.lat)
            val lat2 = Math.toRadians(second.lat)
            var dLon = Math.toRadians(second.lon - first.lon)
            if (dLon > PI) dLon -= 2.0 * PI
            if (dLon < -PI) dLon += 2.0 * PI
            area += dLon * (2.0 + sin(lat1) + sin(lat2))
        }
        return abs(area) * EARTH_RADIUS_M * EARTH_RADIUS_M / 2.0
    }

    fun polygonAreaKm2(ring: List<GeoPoint>): Double =
        polygonAreaM2(ring) / 1_000_000.0

    fun clampBearing(degrees: Double): Double {
        var bearing = degrees % 360.0
        if (bearing < 0) bearing += 360.0
        return bearing
    }

    fun normalizeLongitude(degrees: Double): Double {
        var longitude = (degrees + 180.0) % 360.0
        if (longitude < 0) longitude += 360.0
        return longitude - 180.0
    }

    fun degToRad(degrees: Double): Double = degrees * PI / 180.0
}
