# Phase 3 and Phase 5

## Phase 3 — Visualization

Implemented in `TerrainVisualization.kt`:

- multi-observer visibility heat cells
- visibility-ratio GeoJSON export
- terrain contour extraction using marching squares
- hillshade intensity generation
- bearing-based elevation profile products
- map-SDK-independent result models suitable for polygons, polylines, tile overlays, and GIS export

## Phase 5 — Field operations

Implemented in `FieldOperations.kt`:

- durable field waypoints with GPS accuracy, altitude, eye height, notes, and capture time
- recorded field tracks with calculated distance
- offline-first atomic JSON project persistence
- GPS fix quality gate
- GPX 1.1 waypoint and track export
- waypoint CSV export
- elevation-profile CSV export
- offline field-package manifest

Unit coverage is included in `FieldAndVisualizationTest.kt`.

