# Viewshed Calculator — “Amazing” field pack

**Date:** 2026-07-19  
**Repo:** Viewshading-app (`F:\Viewshading-app`)  
**Approach:** B — keep Maps + View/XML; fix engine; ship premium field UX  
**Status:** Approved by Austin

## Goals

1. Resume unfinished WIP (icons, brand colors, CI secrets, backup rules).
2. Correct radial viewshed LOS (horizon / max elevation-angle method).
3. Make the map usable (collapsible controls) and add pro field features.

## Fixes

- Horizon-based LOS with optional earth curvature + refraction.
- Collapsible bottom sheet (peek actions; expand params).
- Safe elevation keys (rounded lat/lng); cancel-friendly calc; clearer errors.
- Unit tests for destination point + visibility math.
- Land launcher icons, brand palette, CI empty-key build, `.gitignore`.

## Features

1. Observer at my location  
2. Address / place search (Geocoder)  
3. Map type chips: Hybrid / Terrain / Satellite / Normal  
4. Presets: Tree stand, Kayak 2 km, Ridge 10 km, Custom  
5. Target height (m)  
6. Stats after calc: max/avg range, approx area km², ray count  
7. Multi-observer (union of polygons, distinct colors)  
8. Real progress (% rays)  
9. Export GeoJSON + KML with params/stats  
10. Forest green Material 3 theme polish  

## Architecture

```
MainActivity (UI + map)
  → ViewshedEngine (pure Kotlin)
  → ElevationRepository (demo | Google Elevation)
  → Session overlays + export helpers
```

## Out of scope

Local DEM/GeoTIFF, Docker backend, full Compose/Hilt rewrite, accounts.
