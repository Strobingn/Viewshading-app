package com.viewshed.app.viewshed

object GeoExport {

    fun toGeoJson(
        results: List<ViewshedResult>,
        multiObserver: Boolean
    ): String {
        val features = results.mapIndexed { index, result ->
            val polygons = result.visibleSectors.joinToString(",") { sector ->
                val coordinates = closedRing(sector.boundary)
                    .joinToString(",") { "[${it.lon},${it.lat}]" }
                "[[$coordinates]]"
            }
            """
            {
              "type": "Feature",
              "geometry": {
                "type": "MultiPolygon",
                "coordinates": [$polygons]
              },
              "properties": {
                "observer_index": $index,
                "observer_lat": ${result.observer.lat},
                "observer_lon": ${result.observer.lon},
                "eye_height_m": ${result.params.eyeHeightM},
                "target_height_m": ${result.params.targetHeightM},
                "max_dist_km": ${result.params.maxDistKm},
                "num_rays": ${result.stats.numRays},
                "samples_per_ray": ${result.stats.samplesPerRay},
                "visible_cells": ${result.stats.visibleCells},
                "total_cells": ${result.stats.totalCells},
                "max_visible_range_m": ${result.stats.maxRangeM},
                "avg_farthest_visible_range_m": ${result.stats.avgRangeM},
                "visible_area_km2": ${result.stats.areaKm2},
                "demo_terrain": ${result.params.useDemoTerrain},
                "description": "Sampled terrain visibility cells"
              }
            }
            """.trimIndent()
        }.joinToString(",")

        return """
            {
              "type": "FeatureCollection",
              "properties": {
                "multi_observer": $multiObserver,
                "count": ${results.size},
                "overlap_removed": false
              },
              "features": [$features]
            }
        """.trimIndent()
    }

    fun toKml(results: List<ViewshedResult>): String {
        val placemarks = results.mapIndexed { index, result ->
            val color = KML_COLORS[index % KML_COLORS.size]
            val polygons = result.visibleSectors.joinToString("\n") { sector ->
                val coordinates = closedRing(sector.boundary)
                    .joinToString(" ") { "${it.lon},${it.lat},0" }
                """
                <Polygon>
                  <outerBoundaryIs>
                    <LinearRing>
                      <coordinates>$coordinates</coordinates>
                    </LinearRing>
                  </outerBoundaryIs>
                </Polygon>
                """.trimIndent()
            }
            """
            <Placemark>
              <name>Viewshed ${index + 1}</name>
              <description>
                Max ${"%.2f".format(result.stats.maxRangeKm)} km · Visible area ${"%.3f".format(result.stats.areaKm2)} km²
                Eye ${result.params.eyeHeightM} m · Target ${result.params.targetHeightM} m
              </description>
              <Style>
                <PolyStyle><color>$color</color></PolyStyle>
                <LineStyle><color>ffbdbdbd</color><width>1</width></LineStyle>
              </Style>
              <MultiGeometry>
                $polygons
              </MultiGeometry>
            </Placemark>
            <Placemark>
              <name>Observer ${index + 1}</name>
              <Point>
                <coordinates>${result.observer.lon},${result.observer.lat},0</coordinates>
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

    private fun closedRing(points: List<GeoPoint>): List<GeoPoint> {
        if (points.isEmpty() || points.first() == points.last()) return points
        return points + points.first()
    }

    private val KML_COLORS = listOf(
        "99BDBDBD",
        "999E9E9E",
        "99757575",
        "99E0E0E0",
        "99616161"
    )
}
