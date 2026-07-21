package com.viewshed.app.viewshed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ViewshedEngineTest {

    private fun gridFrom(
        observer: GeoPoint,
        params: ViewshedParams,
        elevFn: (GeoPoint) -> Double
    ): ElevationGrid {
        val map = ViewshedEngine.samplePoints(observer, params)
            .associate { it.key() to elevFn(it) }
        return ElevationGrid(map, useDemo = false)
    }

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
        assertTrue("key=$k", k.matches(Regex("""-?\d+\.\d+,-?\d+\.\d+""")))
    }

    @Test
    fun flat_terrain_sees_near_full_range_no_curvature() = runBlocking {
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = 2.0,
            maxDistKm = 1.0,
            numRays = 36,
            samplesPerRay = 40,
            useCurvature = false,
            parallelRays = false,
            adaptiveSampling = false,
            binarySearchHorizon = false
        )
        val grid = gridFrom(observer, params) { 100.0 }
        val result = ViewshedEngine.compute(observer, params, grid)
        assertTrue(
            "expected ~1000m, got ${result.stats.maxRangeM}",
            result.stats.maxRangeM > 950.0
        )
    }

    @Test
    fun ridge_stops_continuous_viewshed_before_far_peak() = runBlocking {
        val observer = GeoPoint(0.0, 0.0)
        val params = ViewshedParams(
            eyeHeightM = 1.0,
            maxDistKm = 1.0,
            numRays = 8,
            samplesPerRay = 40,
            useCurvature = false,
            parallelRays = false,
            adaptiveSampling = false,
            binarySearchHorizon = false
        )
        fun elev(p: GeoPoint): Double {
            val d = GeoMath.distanceM(observer, p)
            return when {
                d in 200.0..350.0 -> 40.0
                d in 650.0..800.0 -> 80.0
                else -> 0.0
            }
        }
        val grid = gridFrom(observer, params, ::elev)
        val result = ViewshedEngine.compute(observer, params, grid)
        assertTrue(
            "continuous range should stop near first ridge, got ${result.stats.maxRangeM}m",
            result.stats.maxRangeM in 150.0..450.0
        )
    }

    @Test
    fun curvature_limits_flat_range_near_geometric_horizon() = runBlocking {
        val eye = 2.0
        val k = 0.13
        val horizon = GeoMath.geometricHorizonM(eye, k)
        val maxKm = (horizon * 2.5) / 1000.0
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = eye,
            maxDistKm = maxKm,
            numRays = 12,
            samplesPerRay = 80,
            useCurvature = true,
            refraction = k,
            parallelRays = false,
            adaptiveSampling = false,
            binarySearchHorizon = false
        )
        val grid = gridFrom(observer, params) { 50.0 }
        val result = ViewshedEngine.compute(observer, params, grid)
        assertTrue(result.stats.maxRangeM < maxKm * 1000.0 * 0.95)
        assertTrue(
            "got ${result.stats.maxRangeM}, horizon≈$horizon",
            abs(result.stats.maxRangeM - horizon) / horizon < 0.45
        )
    }

    @Test
    fun parallel_matches_serial_on_flat() = runBlocking {
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
        val grid = gridFrom(observer, base) { 50.0 }
        val serial = ViewshedEngine.compute(observer, base.copy(parallelRays = false), grid)
        val parallel = ViewshedEngine.compute(observer, base.copy(parallelRays = true), grid)
        assertEquals(serial.stats.maxRangeM, parallel.stats.maxRangeM, 1.0)
    }

    @Test
    fun nearest_neighbor_elev_used_off_lattice() {
        val p0 = GeoPoint(41.5, -74.0)
        val map = mapOf(p0.key() to 123.0)
        val grid = ElevationGrid(map, useDemo = false)
        val nearby = GeoPoint(41.500001, -74.000001)
        assertEquals(123.0, grid.elevation(nearby, maxNeighborM = 50.0), 0.01)
    }

    @Test
    fun geometric_horizon_scales_with_sqrt_height() {
        val h1 = GeoMath.geometricHorizonM(1.7, 0.13)
        val h4 = GeoMath.geometricHorizonM(1.7 * 4, 0.13)
        assertEquals(2.0, h4 / h1, 0.05)
        assertTrue(h1 > 4000.0)
        assertTrue(h1 < 6000.0)
    }

    @Test
    fun refraction_clamp_is_shared_between_params_and_geophysics() {
        assertEquals(0.25, GeoMath.clampRefraction(0.9), 1e-9)
        assertEquals(0.0, GeoMath.clampRefraction(-1.0), 1e-9)
        assertEquals(0.13, GeoMath.clampRefraction(0.13), 1e-9)
        val p = ViewshedParams(refraction = 0.9).sanitized()
        assertEquals(0.25, p.refraction, 1e-9)
        // R_eff must use the same clamp (not silent-only in one path)
        val rWide = GeoMath.effectiveEarthRadiusM(0.9)
        val rClamp = GeoMath.effectiveEarthRadiusM(0.25)
        assertEquals(rClamp, rWide, 1e-6)
    }

    @Test
    fun curvature_drop_matches_reff_form() {
        val d = 10_000.0
        val k = 0.13
        val rEff = GeoMath.effectiveEarthRadiusM(k)
        val drop = (d * d) / (2.0 * rEff)
        val alt = GeoMath.elevationAngleRad(
            observerElev = 100.0,
            targetElev = 100.0,
            distM = d,
            useCurvature = true,
            refractionCoeff = k,
        )
        // Flat equal heights → negative angle equal to atan2(-drop, d)
        assertEquals(kotlin.math.atan2(-drop, d), alt, 1e-12)
    }
}
