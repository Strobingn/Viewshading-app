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

    // AABBGGRR translucent greys (no green)
    private val KML_COLORS = listOf(
        "99BDBDBD",
        "999E9E9E",
        "99757575",
        "99E0E0E0",
        "99616161"
    )
}
