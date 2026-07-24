package com.viewshed.app.viewshed

object GeoExport {

    fun toGeoJson(
        results: List<ViewshedResult>,
        multiObserver: Boolean
    ): String {
        val features = results.mapIndexed { index, r ->
            val sectors = exportSectors(r)
            val coordinates = sectors.joinToString(",") { boundary ->
                val ring = closedRing(boundary).joinToString(",") { "[${it.lon},${it.lat}]" }
                "[[$ring]]"
            }
            """
            {
              "type": "Feature",
              "geometry": {
                "type": "MultiPolygon",
                "coordinates": [$coordinates]
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
                "visible_cells": ${r.stats.visibleCells},
                "total_cells": ${r.stats.totalCells},
                "demo_terrain": ${r.params.useDemoTerrain},
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
        val placemarks = results.mapIndexed { index, r ->
            val color = KML_COLORS[index % KML_COLORS.size]
            val polygons = exportSectors(r).joinToString("\n") { boundary ->
                val coordinates = closedRing(boundary)
                    .joinToString(" ") { "${it.lon},${it.lat},0" }
                """
                <Polygon>
                  <outerBoundaryIs>
                    <LinearRing><coordinates>$coordinates</coordinates></LinearRing>
                  </outerBoundaryIs>
                </Polygon>
                """.trimIndent()
            }
            """
            <Placemark>
              <name>Viewshed ${index + 1}</name>
              <description>
                Max ${"%.2f".format(r.stats.maxRangeKm)} km · Area ${"%.3f".format(r.stats.areaKm2)} km²
                Eye ${r.params.eyeHeightM} m · Target ${r.params.targetHeightM} m
              </description>
              <Style>
                <PolyStyle><color>$color</color></PolyStyle>
                <LineStyle><color>ffbdbdbd</color><width>1</width></LineStyle>
              </Style>
              <MultiGeometry>$polygons</MultiGeometry>
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
            val segments = exportSectors(r).joinToString("\n") { boundary ->
                val points = closedRing(boundary).joinToString("\n") {
                    """<trkpt lat="${it.lat}" lon="${it.lon}"></trkpt>"""
                }
                "<trkseg>$points</trkseg>"
            }
            """
            <trk><name>Viewshed ${i + 1}</name>
            $segments
            </trk>
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

    private fun exportSectors(result: ViewshedResult): List<List<GeoPoint>> =
        result.visibleSectors.map { it.boundary }.ifEmpty { listOf(result.boundary) }

    private fun closedRing(points: List<GeoPoint>): List<GeoPoint> {
        if (points.isEmpty() || points.first() == points.last()) return points
        return points + points.first()
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
