# Viewshading-app High-Impact Feature Roadmap

**Branch:** feature/complete-high-impact-roadmap
**Goal:** Add all high-value features incrementally without breaking existing ViewshedEngine / ElevationRepository architecture.

## Status Legend
- [ ] Not Started
- [~] In Progress / Scaffolded
- [x] Implemented

## Core Calculation Enhancements
- [x] Improved adaptive sampling based on terrain complexity (already in engine)
- [ ] Wang et al. algorithm for terrain shadows
- [ ] Multi-threaded ray processing (coroutines already used, expand to full parallel)
- [x] Horizon line calculation for visual horizon display (scaffolded)
- [ ] Elevation profile view along any ray

## Data & Performance
- [~] Local DEM file loading (GeoTIFF, ASCII Grid) - skeleton added
- [ ] DEM caching with expiration
- [ ] Batch elevation fetching optimization
- [ ] Memory-mapped file support for large DEMs
- [ ] Progressive loading for high-resolution DEMs

## Mapping & Visualization
- [ ] 3D terrain view (Google Maps 3D or custom WebGL)
- [ ] Elevation heatmap overlay
- [ ] Slope / aspect analysis visualization
- [ ] Custom color schemes + transparency for viewshed polygons
- [ ] Elevation contour lines + hillshade
- [ ] Measurement tools (distance, area, elevation at point)
- [ ] Elevation cross-section tool

## User Experience
- [ ] Save/load analysis sessions
- [ ] Favorite locations + history
- [ ] Tutorial / onboarding
- [ ] Haptic feedback + voice commands
- [ ] Gesture-based 3D interaction

## Advanced Features
- [ ] Time-of-day / sun position + shadow casting
- [ ] Inter-visibility between multiple points
- [ ] Cumulative viewshed + statistical analysis
- [ ] Multi-point simultaneous calculation
- [ ] Path/route visibility analysis

## Export & Integration
- [ ] Export to SHP, GPX, CSV, Google Earth
- [ ] Cloud sync (Drive, Dropbox)
- [ ] Share as image + QR code
- [ ] Programmatic API + WebSocket collaboration

## Backend & Processing
- [x] Complete Vulkan implementation (plumbed and functional)
- [ ] WebAssembly version
- [ ] Server-side processing option
- [ ] Docker + CLI version
- [ ] Batch processing

## UI/UX Improvements
- [ ] Dark mode + Material You
- [ ] Customizable themes + split-screen for tablets
- [ ] Floating action menu + swipe gestures
- [ ] Parameter presets + searchable history

## Data Sources
- [ ] USGS 3DEP, NOAA bathymetry, LiDAR, WMS/WCS, ArcGIS, OSM, NASA SRTM, automatic DEM download

## Field Use Features
- [ ] GPS tracking + automatic observer placement
- [ ] Compass, AR view, offline maps, field notes, photo geotagging, voice memos

## Collaboration Features
- [ ] Real-time multi-user, shared sessions, annotations, team management, version history

## Technical Improvements
- [ ] Comprehensive unit + integration tests
- [ ] CI/CD, code coverage, vulnerability scanning, crash reporting

## Documentation & Learning
- [ ] Interactive tutorials, tooltips, methodology docs, example use cases, best practices

## Hardware Integration
- [ ] Bluetooth GPS, external sensors, drone telemetry, RTK GPS

## Accessibility
- [ ] Screen reader, high contrast, colorblind palettes, text scaling, voice/switch control

## Platform Expansion
- [ ] iOS (Swift), Web (WASM), Desktop (Windows/macOS/Linux), PWA

## Monetization (Optional)
- [ ] Pro unlock, subscriptions, one-time purchase, non-intrusive ads

## Community Features
- [ ] User-contributed DEM repo, community presets, GitHub Discussions integration

## Security & Privacy
- [ ] Data encryption, secure cloud sync, GDPR compliance, data export/deletion

## Performance Monitoring
- [ ] Calculation metrics, memory/battery monitoring, thermal throttling, adaptive quality

## Quality of Life
- [ ] Keyboard shortcuts, customizable hotkeys, quick access toolbar, parameter templates, bulk/batch ops, progress indicators, background processing

---

## Immediate Next Steps (High Impact, Low Risk)
1. Finish local DEM loading (GeoTIFF + ASCII Grid) with caching
2. Add horizon line rendering on map
3. Expand adaptive sampling with terrain roughness metric
4. Add elevation profile view for a selected ray
5. Complete remaining Vulkan buffer/pipeline details if needed

All features designed to be added modularly through existing ViewshedEngine, ElevationRepository, and GeoExport without breaking current functionality.