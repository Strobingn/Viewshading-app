# Viewshading-app Master Roadmap

**Branch:** `feature/complete-high-impact-roadmap`  
**App version:** **1.6.0** (versionCode 9)

## Status legend

- [x] Shipped & wired in UI
- [~] Partial / foundation
- [ ] Planned

---

## Phase 1–3 — Field tools (done, v1.5)

- [x] Offline elevation packs
- [x] Field notes + map markers
- [x] Voice memos + geotag index
- [x] Measure mode

---

## Phase 4 — Professional Analysis ✅ (v1.6)

GIS-style analysis in `ProfessionalAnalysis.kt` + UI under **Professional analysis**.

| Feature | Status | Notes |
|---------|--------|--------|
| Intervisibility | [x] | Multi-observer matrix; green/red link lines |
| Cumulative viewsheds | [x] | Union approx + overlap grid |
| Visibility frequency | [x] | Heat markers by observer count |
| Visibility weighting | [x] | Near/far distance-weighted score |
| Path visibility | [x] | Tap path vertices → % LOS from observer |
| Shadow analysis | [x] | Solar position + sun-lit footprint |
| Solar position | [x] | `SolarPosition` (NOAA-style) |
| Multi-observer analysis | [x] | Pairwise matrix + summary |

---

## Phase 5 — Field Operations ✅ / partial

| Feature | Status |
|---------|--------|
| GPS auto-placement | [x] (FAB + fused location) |
| Compass integration | [x] (`CompassHelper` live heading) |
| Offline maps (elevation packs) | [x] |
| AR camera overlay | [ ] (archived experimental) |
| Geotagged photos | [x] (EXIF via picker) |
| Voice notes | [x] |
| Field forms | [x] |
| Favorites | [x] |
| Session history | [x] (searchable list) |

---

## Phase 6 — Data Sources ✅ / partial

| Source | Status |
|--------|--------|
| Google Elevation | [x] |
| USGS 3DEP | [x] via Open-Topo-Data `ned10m` |
| NASA SRTM | [x] via Open-Topo-Data `srtm90m` |
| NOAA bathymetry | [x] via ETOPO1 |
| ArcGIS / WMS / WCS | [ ] |
| OpenStreetMap elev | [~] (Open-Topo-Data) |
| Automatic DEM download | [~] (offline pack capture) |
| **Local DEM terrain engine** (ASC/CSV + bilinear) | [x] `TerrainEngine` / `TerrainGrid` + UI Load DEM |
| LiDAR visualization | [ ] (archived) |

---

## Phase 7 — Export ✅ / partial

| Format | Status |
|--------|--------|
| GeoJSON | [x] |
| KML | [x] |
| GPX | [x] |
| CSV | [x] |
| SHP / KMZ / image / QR / cloud | [ ] |

---

## Phase 8 — GPU Computing [~]

| Feature | Status |
|---------|--------|
| Vulkan bridge | [~] optional `VulkanViewshed` |
| Perf timing dashboard | [x] `PerformanceMonitor` + `tvPerf` |
| Full GPU ray marching | [ ] |
| Thermal monitoring | [ ] |

---

## Phase 9 — Collaboration [ ]

Shared sessions, live collab, comments, teams, permissions, version history.

---

## Phase 10 — UI Modernization [~]

| Feature | Status |
|---------|--------|
| Dark mode / theme | [x] |
| Material 3 components | [x] |
| Material You dynamic color | [ ] |
| Tablet layouts | [ ] |
| Parameter presets | [x] |
| Searchable history | [x] |
| Floating actions | [x] (FABs) |

---

## Phase 11 — Reliability [~]

| Feature | Status |
|---------|--------|
| Unit tests (engine + field + pro analysis) | [x] |
| CI/CD GitHub Actions | [x] |
| Integration / coverage / Sentry | [ ] |

---

## Phase 12 — Documentation [~]

`ROADMAP.md`, `AGENTS.md`, `docs/MAPS_SETUP.md`. Tutorials / interactive help pending.

---

## Phase 13 — Accessibility [ ]

Screen reader, high contrast, colorblind palettes, text scaling, voice/switch access.

---

## Phase 14 — Platform Expansion [ ]

Android primary. Engine is pure Kotlin-ready for future desktop/WASM; no multi-platform packaging yet.

---

## Additional high-value modules (planned)

- AI Terrain Assistant  
- Automatic Observation Point Optimizer  
- RF / Radio LOS  
- Ballistic LOS  
- Drone Mission Planner  
- Camera Simulator  
- Full AR ridgelines  
- Live GPU dashboard (FPS / temps)

---

## How to use Phase 4–6 (quick)

1. Place observers (multi-observer on for intervis).  
2. Pick elevation source: Google / USGS / SRTM / ETOPO.  
3. **Calculate**.  
4. **Intervis** / **Cumulative** / **Frequency** / **Sun shadow** / **Weighted**.  
5. **Path LOS**: tap Path → add vertices → Run path.  
6. Export GeoJSON / KML / GPX / CSV.

```powershell
cd F:\Viewshading-app
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\scripts\android-cli-run.ps1 -SkipBuild
```
