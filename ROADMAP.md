# Viewshading-app High-Impact Feature Roadmap

**Branch:** `feature/complete-high-impact-roadmap`  
**App version:** 1.5.0 (versionCode 8)

## Status legend

- [x] Done and wired into `MainActivity`
- [~] Scaffold / partial
- [ ] Not started

## Recently completed — Field tools phases (2026-07-20)

### Phase 1 — Offline elevation packs
- [x] `OfflineMapCache` — JSON packs of elevation samples around a center/radius
- [x] Capture grid via Elevation API / demo terrain
- [x] “Use offline elevation only” switch for field use without network
- [x] Manage / delete packs UI
- [x] Merge into `ElevationRepository.resolveElevations`

### Phase 2 — Field notes
- [x] Persistent notes (`filesDir/field_notes.json`)
- [x] Add note at observer / map center
- [x] List, open, delete, fly-to
- [x] Yellow map markers (tap for detail)

### Phase 3 — Voice memos
- [x] AAC/M4A recording with geotag metadata
- [x] Record / stop button + `RECORD_AUDIO` permission
- [x] List, play, delete
- [x] Magenta map markers (tap to play)

### Bonus (same pass)
- [x] Measure mode (two-tap distance) using `MeasurementTool` + `GeoMath`
- [x] Restored working CPU `ViewshedEngine` from `main` (branch had broken Vulkan-only stub)

## Core product (stable)

- [x] Horizon-based radial viewshed
- [x] Demo terrain + Google Elevation API
- [x] Collapsible bottom sheet, presets, quality chips
- [x] Multi-observer, GeoJSON/KML export, stats, progress
- [x] Theme (system/light/dark)
- [x] Session save (`last_session.json`)

## Experimental / scaffold modules (not fully productized)

- [~] Vulkan compute path (`VulkanViewshed`)
- [~] LiDAR / hillshade / historical overlays / AR / SLAM classes under `viewshed/`
- [ ] Full GeoTIFF DEM loader
- [ ] Cloud sync / collaboration

## How to run field tools

1. Place observer (long-press or My Location).
2. Expand bottom sheet → **Field tools**.
3. **Cache offline pack** (uses max-distance as radius).
4. Optionally enable **Use offline elevation only**.
5. **Add note** / **Record memo** at current observer or map center.
6. Enable **Measure distance** and tap two map points.

```powershell
cd F:\Viewshading-app
.\scripts\android-cli-build.ps1
.\scripts\android-cli-run.ps1 -SkipBuild
```
