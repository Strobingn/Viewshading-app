package com.viewshed.app.viewshed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class ViewshedEngineTest {

    private fun gridFrom(
        observer: GeoPoint,
        params: ViewshedParams,
        elevation: (GeoPoint) -> Double
    ): ElevationGrid {
        val map = ViewshedEngine.samplePoints(observer, params)
            .associate { it.key() to elevation(it) }
        return ElevationGrid(map, useDemo = false)
    }

    @Test
    fun destination_north_moves_latitude_up() {
        val start = GeoPoint(41.5, -74.0)
        val destination = GeoMath.destination(start, 0.0, 1000.0)
        assertTrue(destination.lat > start.lat)
        assertEquals(start.lon, destination.lon, 0.01)
    }

    @Test
    fun key_uses_us_dot_decimals() {
        val key = GeoPoint(41.5, -74.01).key()
        assertTrue("key=$key", key.matches(Regex("""-?\d+\.\d+,-?\d+\.\d+""")))
    }

    @Test
    fun flat_terrain_sees_full_range_and_full_circle_area() = runBlocking {
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = 2.0,
            targetHeightM = 0.0,
            maxDistKm = 1.0,
            numRays = 36,
            samplesPerRay = 20,
            useDemoTerrain = false,
            useCurvature = false,
            parallelRays = false
        )
        val result = ViewshedEngine.compute(
            observer,
            params,
            gridFrom(observer, params) { 100.0 }
        )

        assertEquals(1000.0, result.stats.maxRangeM, 0.01)
        assertEquals(result.stats.totalCells, result.stats.visibleCells)
        assertEquals(PI, result.stats.areaKm2, 0.02)
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
            useDemoTerrain = false,
            useCurvature = false,
            parallelRays = false
        )
        val result = ViewshedEngine.compute(
            observer,
            params,
            gridFrom(observer, params) { 0.0 }
        )

        assertEquals(1000.0, result.stats.maxRangeM, 0.01)
        assertTrue(result.visibilityRays.all { ray -> ray.samples.all { it.visible } })
    }

    @Test
    fun visible_peak_beyond_ridge_does_not_fill_hidden_valley() = runBlocking {
        val observer = GeoPoint(0.0, 0.0)
        val params = ViewshedParams(
            eyeHeightM = 2.0,
            targetHeightM = 0.0,
            maxDistKm = 1.0,
            numRays = 8,
            samplesPerRay = 10,
            useDemoTerrain = false,
            useCurvature = false,
            parallelRays = false
        )
        fun elevation(point: GeoPoint): Double {
            val distance = GeoMath.distanceM(observer, point)
            return when {
                distance in 250.0..350.0 -> 50.0
                distance >= 950.0 -> 200.0
                else -> 0.0
            }
        }

        val result = ViewshedEngine.compute(
            observer,
            params,
            gridFrom(observer, params, ::elevation)
        )
        val firstRay = result.visibilityRays.first().samples

        assertTrue(firstRay[2].visible)
        (3..8).forEach { index -> assertFalse(firstRay[index].visible) }
        assertTrue(firstRay[9].visible)
        assertEquals(1000.0, result.stats.maxRangeM, 0.01)
        assertEquals(2 * params.numRays, result.visibleSectors.size)
        assertTrue(result.stats.visibleCells < result.stats.totalCells)
    }

    @Test
    fun missing_real_elevation_stops_instead_of_using_demo_terrain() = runBlocking {
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            numRays = 8,
            samplesPerRay = 10,
            useDemoTerrain = false,
            parallelRays = false
        )
        val incompleteGrid = ElevationGrid(
            mapOf(observer.key() to 100.0),
            useDemo = false
        )

        var stopped = false
        try {
            ViewshedEngine.compute(observer, params, incompleteGrid)
        } catch (_: ElevationDataException) {
            stopped = true
        }
        assertTrue("Missing real elevation must stop the calculation", stopped)
    }

    @Test
    fun curvature_horizon_matches_effective_earth_radius() = runBlocking {
        val observer = GeoPoint(41.5, -74.0)
        val params = ViewshedParams(
            eyeHeightM = 1.7,
            targetHeightM = 0.0,
            maxDistKm = 10.0,
            numRays = 8,
            samplesPerRay = 250,
            useDemoTerrain = false,
            useCurvature = true,
            refraction = 0.13,
            parallelRays = false
        )
        val result = ViewshedEngine.compute(
            observer,
            params,
            gridFrom(observer, params) { 0.0 }
        )
        val expectedHorizonM = sqrt(
            2.0 * GeoMath.EARTH_RADIUS_M * params.eyeHeightM /
                (1.0 - params.refraction)
        )

        assertEquals(expectedHorizonM, result.stats.maxRangeM, 100.0)
    }

    @Test
    fun parallel_matches_serial() = runBlocking {
        val observer = GeoPoint(41.5, -74.0)
        val base = ViewshedParams(
            eyeHeightM = 2.0,
            maxDistKm = 0.5,
            numRays = 24,
            samplesPerRay = 15,
            useDemoTerrain = false,
            useCurvature = false
        )
        val grid = gridFrom(observer, base) { 50.0 }

        val serial = ViewshedEngine.compute(
            observer,
            base.copy(parallelRays = false),
            grid
        )
        val parallel = ViewshedEngine.compute(
            observer,
            base.copy(parallelRays = true),
            grid
        )

        assertEquals(serial.stats.maxRangeM, parallel.stats.maxRangeM, 0.01)
        assertEquals(serial.stats.visibleCells, parallel.stats.visibleCells)
        assertEquals(serial.stats.areaKm2, parallel.stats.areaKm2, 1e-9)
    }

    @Test
    fun invalid_horizon_options_are_disabled_by_sanitization() {
        val params = ViewshedParams(
            adaptiveSampling = true,
            binarySearchHorizon = true
        ).sanitized()

        assertFalse(params.adaptiveSampling)
        assertFalse(params.binarySearchHorizon)
    }

    @Test
    fun geometric_horizon_scales_with_square_root_of_height() {
        val low = GeoMath.geometricHorizonM(1.7, 0.13)
        val high = GeoMath.geometricHorizonM(1.7 * 4, 0.13)
        assertEquals(2.0, high / low, 0.05)
        assertTrue(low in 4000.0..6000.0)
    }

    @Test
    fun quality_presets_apply_ray_counts() {
        val params = ViewshedParams().withQuality(SampleQuality.HIGH)
        assertEquals(SampleQuality.HIGH.rays, params.numRays)
        assertEquals(SampleQuality.HIGH.samples, params.samplesPerRay)
    }
}
