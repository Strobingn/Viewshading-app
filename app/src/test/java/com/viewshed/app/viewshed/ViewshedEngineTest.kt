package com.viewshed.app.viewshed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(result.stats.totalCells, result.stats.visibleCells)
        assertEquals(Math.PI, result.stats.areaKm2, 0.02)
    }

    @Test
    fun target_height_is_not_used_as_an_intervening_obstacle() = runBlocking {
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = 1.7,
            targetHeightM = 10.0,
            maxDistKm = 1.0,
            numRays = 8,
            samplesPerRay = 20,
            useCurvature = false,
            parallelRays = false,
        )
        val result = ViewshedEngine.compute(observer, params, gridFrom(observer, params) { 0.0 })

        assertEquals(1000.0, result.stats.maxRangeM, 0.01)
        assertTrue(result.visibilityRays.all { ray -> ray.samples.all { it.visible } })
    }

    @Test
    fun visible_peak_beyond_ridge_does_not_fill_hidden_valley() = runBlocking {
        val observer = GeoPoint(0.0, 0.0)
        val params = ViewshedParams(
            eyeHeightM = 2.0,
            maxDistKm = 1.0,
            numRays = 8,
            samplesPerRay = 10,
            useCurvature = false,
            parallelRays = false,
        )
        fun elev(p: GeoPoint): Double {
            val d = GeoMath.distanceM(observer, p)
            return when {
                d in 250.0..350.0 -> 50.0
                d >= 950.0 -> 200.0
                else -> 0.0
            }
        }
        val grid = gridFrom(observer, params, ::elev)
        val result = ViewshedEngine.compute(observer, params, grid)
        val firstRay = result.visibilityRays.first().samples

        assertTrue(firstRay[2].visible)
        (3..8).forEach { index -> assertFalse(firstRay[index].visible) }
        assertTrue(firstRay[9].visible)
        assertEquals(1000.0, result.stats.maxRangeM, 0.01)
        assertEquals(2 * params.numRays, result.visibleSectors.size)
        assertTrue(result.stats.visibleCells < result.stats.totalCells)
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
    fun missing_real_elevation_fails_closed() {
        val p0 = GeoPoint(41.5, -74.0)
        val map = mapOf(p0.key() to 123.0)
        val grid = ElevationGrid(map, useDemo = false)
        val nearby = GeoPoint(41.500001, -74.000001)
        var failed = false
        try {
            grid.elevation(nearby)
        } catch (_: ElevationDataException) {
            failed = true
        }
        assertTrue("missing real elevation must not use a nearby or demo value", failed)
    }

    @Test
    fun non_monotonic_horizon_options_are_disabled() {
        val sanitized = ViewshedParams(
            adaptiveSampling = true,
            binarySearchHorizon = true,
        ).sanitized()

        assertFalse(sanitized.adaptiveSampling)
        assertFalse(sanitized.binarySearchHorizon)
    }

    @Test
    fun geometric_horizon_scales_with_sqrt_height() {
        val h1 = GeoMath.geometricHorizonM(1.7, 0.13)
        val h4 = GeoMath.geometricHorizonM(1.7 * 4, 0.13)
        assertEquals(2.0, h4 / h1, 0.05)
        assertTrue(h1 > 4000.0)
        assertTrue(h1 < 6000.0)
    }
}
