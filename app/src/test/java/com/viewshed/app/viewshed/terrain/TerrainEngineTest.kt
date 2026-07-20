package com.viewshed.app.viewshed.terrain

import com.viewshed.app.viewshed.GeoPoint
import com.viewshed.app.viewshed.ViewshedEngine
import com.viewshed.app.viewshed.ViewshedParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TerrainEngineTest {

    @Test
    fun generateDemoRegionHasValidStats() {
        val grid = TerrainEngine.generateDemoRegion(
            center = GeoPoint(41.5, -74.0),
            halfSizeM = 2000.0,
            cellSizeM = 50.0,
        )
        assertTrue(grid.ncols > 10)
        assertTrue(grid.nrows > 10)
        val s = grid.stats()
        assertTrue(s.validCells > 0)
        assertTrue(s.maxElevM > s.minElevM)
    }

    @Test
    fun bilinearSampleInsideGrid() {
        val grid = TerrainEngine.generateDemoRegion(cellSizeM = 80.0)
        val mid = GeoPoint((grid.south + grid.north) / 2, (grid.west + grid.east) / 2)
        val z = grid.sampleBilinear(mid.lat, mid.lon)
        assertNotNull(z)
    }

    @Test
    fun loadEsriAsciiRoundTrip() {
        val asc =
            """
            ncols 3
            nrows 3
            xllcorner -74.02
            yllcorner 41.49
            cellsize 0.01
            NODATA_value -9999
            10 20 30
            40 50 60
            70 80 90
            """.trimIndent()
        val grid = TerrainEngine.loadEsriAscii(ByteArrayInputStream(asc.toByteArray()), "t.asc")
        assertEquals(3, grid.ncols)
        assertEquals(3, grid.nrows)
        // northern row first in ASC: 10,20,30
        assertEquals(10f, grid.elevations[0])
        assertEquals(50f, grid.elevationAt(1, 1))
        val z = grid.sampleBilinear(41.50, -74.01)
        assertNotNull(z)
    }

    @Test
    fun viewshedUsesLocalTerrainSurface() = runBlocking {
        val terrain = TerrainEngine.generateDemoRegion(
            center = GeoPoint(41.503, -74.01),
            halfSizeM = 3000.0,
            cellSizeM = 60.0,
        )
        val observer = GeoPoint(41.503, -74.01)
        val params = ViewshedParams(
            maxDistKm = 1.0,
            numRays = 24,
            samplesPerRay = 30,
            useDemoTerrain = false,
            parallelRays = false,
        )
        val samples = ViewshedEngine.samplePoints(observer, params)
        val elev = TerrainEngine.toElevationGrid(terrain, samples)
        assertNotNull(elev.terrain)
        val result = ViewshedEngine.compute(observer, params, elev)
        assertTrue(result.rangesM.isNotEmpty())
        assertTrue(result.stats.maxRangeM > 0.0)
    }
}
