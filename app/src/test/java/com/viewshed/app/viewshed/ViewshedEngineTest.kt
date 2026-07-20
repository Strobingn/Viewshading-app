package com.viewshed.app.viewshed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ViewshedEngineTest {

    @Test
    fun destination_north_moves_latitude_up() {
        val start = GeoPoint(41.5, -74.0)
        val dest = GeoMath.destination(start, 0.0, 1000.0)
        assertTrue(dest.lat > start.lat)
        assertEquals(start.lon, dest.lon, 0.01)
    }

    @Test
    fun key_uses_us_dot_decimals() {
        val k = GeoPoint(41.5, -74.01).key()
        // "41.500000,-74.010000" — dots for decimals, one comma between lat/lon
        assertTrue("key=$k", k.matches(Regex("""-?\d+\.\d+,-?\d+\.\d+""")))
    }

    @Test
    fun flat_terrain_sees_near_full_range_no_curvature() {
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = 2.0,
            targetHeightM = 0.0,
            maxDistKm = 1.0,
            numRays = 36,
            samplesPerRay = 40,
            useCurvature = false,
            parallelRays = false,
            adaptiveSampling = false,
            binarySearchHorizon = false
        )
        val elev = ViewshedEngine.samplePoints(observer, params).associate { it.key() to 100.0 }
        val result = ViewshedEngine.compute(observer, params, elev)
        // Last sample is at maxDist; continuous LOS on flat should reach it
        assertTrue(
            "expected ~1000m, got ${result.stats.maxRangeM}",
            result.stats.maxRangeM > 950.0
        )
    }

    @Test
    fun ridge_stops_continuous_viewshed_before_far_peak() {
        val observer = GeoPoint(0.0, 0.0)
        val params = ViewshedParams(
            eyeHeightM = 1.0,
            targetHeightM = 0.0,
            maxDistKm = 1.0,
            numRays = 8,
            samplesPerRay = 40,
            useCurvature = false,
            parallelRays = false,
            adaptiveSampling = false,
            binarySearchHorizon = false
        )
        // Wall at ~250–350 m, then a TALLER peak at ~700 m that LOS could see
        // over the valley — continuous lobe must NOT jump to the far peak.
        fun elev(p: GeoPoint): Double {
            val d = GeoMath.distanceM(observer, p)
            return when {
                d in 200.0..350.0 -> 40.0
                d in 650.0..800.0 -> 80.0
                else -> 0.0
            }
        }
        val elevMap = ViewshedEngine.samplePoints(observer, params)
            .associate { it.key() to elev(it) }
        val result = ViewshedEngine.compute(observer, params, elevMap)
        assertTrue(
            "continuous range should stop near first ridge, got ${result.stats.maxRangeM}m",
            result.stats.maxRangeM in 150.0..450.0
        )
    }

    @Test
    fun curvature_limits_flat_range_near_geometric_horizon() {
        val eye = 2.0
        val k = 0.13
        val horizon = GeoMath.geometricHorizonM(eye, k)
        // Sample out past the geometric horizon
        val maxKm = (horizon * 2.5) / 1000.0
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = eye,
            targetHeightM = 0.0,
            maxDistKm = maxKm,
            numRays = 12,
            samplesPerRay = 80,
            useCurvature = true,
            refraction = k,
            parallelRays = false,
            adaptiveSampling = false,
            binarySearchHorizon = false
        )
        val elev = ViewshedEngine.samplePoints(observer, params).associate { it.key() to 50.0 }
        val result = ViewshedEngine.compute(observer, params, elev)
        // Should not reach full maxDist; should be on order of geometric horizon
        assertTrue(result.stats.maxRangeM < maxKm * 1000.0 * 0.95)
        assertTrue(
            "got ${result.stats.maxRangeM}, horizon≈$horizon",
            abs(result.stats.maxRangeM - horizon) / horizon < 0.45
        )
    }

    @Test
    fun elevation_angle_flat_is_negative_looking_down() {
        val a = GeoMath.elevationAngleRad(
            observerElev = 12.0,
            targetElev = 10.0,
            distM = 100.0,
            useCurvature = false,
            refractionCoeff = 0.13
        )
        assertTrue(a < 0.0)
    }

    @Test
    fun effective_radius_grows_with_refraction() {
        val r0 = GeoMath.effectiveEarthRadiusM(0.0)
        val rK = GeoMath.effectiveEarthRadiusM(0.13)
        assertEquals(GeoMath.EARTH_RADIUS_M, r0, 1.0)
        assertTrue(rK > r0)
    }

    @Test
    fun parallel_matches_serial_on_flat() {
        val observer = GeoPoint(41.5, -74.0)
        val base = ViewshedParams(
            eyeHeightM = 2.0,
            maxDistKm = 0.5,
            numRays = 24,
            samplesPerRay = 20,
            useCurvature = false,
            adaptiveSampling = false,
            binarySearchHorizon = false
        )
        val elev = ViewshedEngine.samplePoints(observer, base)
            .associate { it.key() to 50.0 }
        val serial = ViewshedEngine.compute(observer, base.copy(parallelRays = false), elev)
        val parallel = ViewshedEngine.compute(observer, base.copy(parallelRays = true), elev)
        assertEquals(serial.stats.maxRangeM, parallel.stats.maxRangeM, 1.0)
    }

    @Test
    fun geometric_horizon_scales_with_sqrt_height() {
        val h1 = GeoMath.geometricHorizonM(1.7, 0.13)
        val h4 = GeoMath.geometricHorizonM(1.7 * 4, 0.13)
        assertEquals(2.0, h4 / h1, 0.05)
        assertTrue(h1 > 4000.0) // ~4.7 km ballpark for 1.7 m
        assertTrue(h1 < 6000.0)
    }
}
