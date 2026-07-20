package com.viewshed.app.viewshed

object GeoExport {

    fun toGeoJson(
        results: List<ViewshedResult>,
        multiObserver: Boolean
    ): String {
        val features = results.mapIndexed { index, r ->
            val coords = r.boundary.joinToString(",") { "[${it.lon},${it.lat}]" }
            """
            {
              "type": "Feature",
              "geometry": {
                "type": "Polygon",
                "coordinates": [[$coords]]
              },
              "properties": {
                "observer_index": $index,
                "observer_lat": ${r.observer.lat},
                "observer_lon": ${r.observer.lon},
                "eye_height_m": ${r.params.eyeHeightM},
                "target_height_m": ${r.params.targetHeightM},
                "max_dist_km": ${r.params.maxDistKm},
                "num_rays": ${r.stats.numRays},
                "max_range_m": ${r.stats.maxRangeM},
                "avg_range_m": ${r.stats.avgRangeM},
                "area_km2": ${r.stats.areaKm2},
                "demo_terrain": ${r.params.useDemoTerrain},
                "description": "Viewshed visible area"
              }
            }
            """.trimIndent()
        }.joinToString(",")

        return """
            {
              "type": "FeatureCollection",
              "properties": {
                "multi_observer": $multiObserver,
                "count": ${results.size}
              },
              "features": [$features]
            }
        """.trimIndent()
    }

    fun toKml(results: List<ViewshedResult>): String {
        val placemarks = results.mapIndexed { index, r ->
            val coords = r.boundary.joinToString(" ") { "${it.lon},${it.lat},0" }
            val color = KML_COLORS[index % KML_COLORS.size]
            """
            <Placemark>
              <name>Viewshed ${index + 1}</name>
              <description>
                Max ${"%.2f".format(r.stats.maxRangeKm)} km · Area ${"%.3f".format(r.stats.areaKm2)} km²
                Eye ${r.params.eyeHeightM} m · Target ${r.params.targetHeightM} m
              </description>
              <Style>
                <PolyStyle><color>$color</color></PolyStyle>
                <LineStyle><color>ff40a000</color><width>2</width></LineStyle>
              </Style>
              <Polygon>
                <outerBoundaryIs>
                  <LinearRing>
                    <coordinates>$coords</coordinates>
                  </LinearRing>
                </outerBoundaryIs>
              </Polygon>
            </Placemark>
            <Placemark>
              <name>Observer ${index + 1}</name>
              <Point>
                <coordinates>${r.observer.lon},${r.observer.lat},0</coordinates>
              </Point>
            </Placemark>
            """.trimIndent()
        }.joinToString("\n")

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>Viewshed Export</name>
                $placemarks
              </Document>
            </kml>
        """.trimIndent()
    }

    /** Phase 7 — GPX waypoints + track of boundary. */
    fun toGpx(results: List<ViewshedResult>): String {
        val wpts = results.mapIndexed { i, r ->
            """
            <wpt lat="${r.observer.lat}" lon="${r.observer.lon}">
              <name>Observer ${i + 1}</name>
              <desc>Eye ${r.params.eyeHeightM} m · Area ${"%.3f".format(r.stats.areaKm2)} km²</desc>
            </wpt>
            """.trimIndent()
        }.joinToString("\n")
        val trks = results.mapIndexed { i, r ->
            val pts = r.boundary.joinToString("\n") {
                """<trkpt lat="${it.lat}" lon="${it.lon}"></trkpt>"""
            }
            """
            <trk><name>Viewshed ${i + 1}</name><trkseg>
            $pts
            </trkseg></trk>
            """.trimIndent()
        }.joinToString("\n")
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Viewshed Calculator">
            $wpts
            $trks
            </gpx>
        """.trimIndent()
    }

    /** Phase 7 — CSV of observers + stats. */
    fun toCsv(results: List<ViewshedResult>): String {
        val header = "index,observer_lat,observer_lon,eye_m,target_m,max_dist_km,max_range_km,avg_range_km,area_km2,rays"
        val rows = results.mapIndexed { i, r ->
            listOf(
                i,
                r.observer.lat,
                r.observer.lon,
                r.params.eyeHeightM,
                r.params.targetHeightM,
                r.params.maxDistKm,
                r.stats.maxRangeKm,
                r.stats.avgRangeKm,
                r.stats.areaKm2,
                r.stats.numRays
            ).joinToString(",")
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    fun frequencyToGeoJson(cells: List<ProfessionalAnalysis.FrequencyCell>, maxCount: Int): String {
        val features = cells.map { c ->
            val intensity = if (maxCount > 0) c.count.toDouble() / maxCount else 0.0
            """
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [${c.point.lon}, ${c.point.lat}] },
              "properties": {
                "count": ${c.count},
                "weight": ${c.weight},
                "intensity": $intensity
              }
            }
            """.trimIndent()
        }.joinToString(",")
        return """{"type":"FeatureCollection","features":[$features]}"""
    }

    // AABBGGRR translucent greys (no green)
    private val KML_COLORS = listOf(
        "99BDBDBD",
        "999E9E9E",
        "99757575",
        "99E0E0E0",
        "99616161"
    )
}
