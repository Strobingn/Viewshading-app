package com.viewshed.app.viewshed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendViewshedClientTest {

    @Test
    fun parses_multi_polygon_visibility_sectors() {
        val observer = GeoPoint(0.0, 0.0)
        val params = ViewshedParams(
            numRays = 8,
            samplesPerRay = 10,
            maxDistKm = 1.0,
        )
        val response =
            """
            {
              "type":"FeatureCollection",
              "features":[{
                "type":"Feature",
                "geometry":{"type":"MultiPolygon","coordinates":[
                  [[[0.0,0.0],[0.001,0.0],[0.001,0.001],[0.0,0.0]]],
                  [[[0.002,0.0],[0.003,0.0],[0.003,0.001],[0.002,0.0]]]
                ]},
                "properties":{
                  "ranges_m":[1000,1000,1000,1000,1000,1000,1000,1000],
                  "visible_area_km2":0.42,
                  "visible_cells":32,
                  "total_cells":80,
                  "sectors":[
                    {"bearing_start_deg":337.5,"bearing_end_deg":22.5,"inner_distance_m":0,"outer_distance_m":300,"visible_cell_count":3,"area_m2":17671.5},
                    {"bearing_start_deg":337.5,"bearing_end_deg":22.5,"inner_distance_m":900,"outer_distance_m":1000,"visible_cell_count":1,"area_m2":37306.4}
                  ]
                }
              }]
            }
            """.trimIndent()

        val result = BackendViewshedClient("http://localhost").parseGeoJsonViewshed(
            response,
            observer,
            params,
        )

        assertEquals(2, result.visibleSectors.size)
        assertEquals(32, result.stats.visibleCells)
        assertEquals(80, result.stats.totalCells)
        assertEquals(0.42, result.stats.areaKm2, 0.0001)
        assertEquals(1000.0, result.stats.maxRangeM, 0.01)
        assertTrue(result.boundary.first() == result.boundary.last())
    }

    @Test
    fun normalizes_backend_url() {
        assertEquals(
            "http://127.0.0.1:8000",
            BackendViewshedClient.normalizeUrl("127.0.0.1:8000/"),
        )
    }
}
