# Viewshed engine features

## Working CPU analysis

| Feature | Status | Notes |
|---|---|---|
| Fixed-grid radial visibility | Working | Preserves visible and hidden cells along every ray |
| Parallel rays | Working | Coroutine per ray on `Dispatchers.Default` |
| Quality presets | Working | Sets ray and radial sample density |
| Earth curvature + refraction | Working | Effective-Earth-radius spherical calculation |
| Target height | Working | Applied to candidate targets, not intervening terrain |
| Real elevation integrity | Working | Real mode fails clearly instead of mixing demo terrain |
| Analysis session save | Working | Stores outer extent, ranges, and visible sectors |
| GeoJSON / KML | Working | Exports sampled visible sectors as multipolygons |

## Disabled correctness hazards

- Adaptive sampling is disabled because its generated locations were not part of the
  prefetched elevation grid and silently received demo elevations.
- Binary-search horizon is disabled because visibility over real terrain is not a
  monotonic true/false function. A farther peak can be visible behind a hidden valley.

These options remain in the parameter model only so older saved sessions can still load.

## Optional native path

- `app/src/main/cpp/viewshed_vulkan.cpp` remains experimental.
- `VulkanViewshed` loads the library only when present.
- The production calculation continues to use the tested CPU engine.

## Accuracy boundary

The result is a sampled bare-earth terrain viewshed. It does not include buildings,
trees, or other surface obstructions unless their heights are supplied by a future
DSM/local DEM source. Increasing rays and samples reduces gaps but cannot add details
that are absent from the elevation source.
