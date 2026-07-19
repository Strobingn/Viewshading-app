# Viewshading-app High-Impact Feature Roadmap

**Branch:** feature/complete-high-impact-roadmap

## Recently Completed (this session)
- [x] Local DEM file loading (ASCII Grid support + skeleton for GeoTIFF)
- [x] Horizon line calculation for visual horizon display
- [x] Elevation profile view along any ray
- [x] Save/load analysis sessions (basic)
- [x] Export to GPX + CSV (expanded GeoExport)
- [x] GPS tracking + automatic observer placement helper

## Core Calculation Enhancements
- [x] Improved adaptive sampling based on terrain complexity
- [x] Horizon line calculation
- [ ] Wang et al. algorithm for terrain shadows
- [ ] Multi-threaded ray processing (expand coroutines)
- [ ] Elevation profile view (done)

## Data & Performance
- [x] Local DEM file loading (ASCII Grid)
- [~] DEM caching
- [ ] Batch elevation fetching
- [ ] Memory-mapped files
- [ ] Progressive DEM loading

## Mapping & Visualization
- [ ] 3D terrain view
- [ ] Elevation heatmap / slope / aspect viz
- [ ] Custom color schemes + transparency
- [ ] Contour lines + hillshade
- [ ] Measurement tools
- [x] Elevation cross-section / profile (done)

## User Experience
- [x] Save/load analysis sessions
- [ ] Favorites + history
- [ ] Tutorial / onboarding
- [ ] Haptic + voice

## Advanced Features
- [ ] Time-of-day / sun + shadow casting
- [ ] Inter-visibility
- [ ] Cumulative viewshed + stats
- [ ] Multi-point calculation
- [ ] Path visibility analysis

## Export & Integration
- [x] GPX + CSV export
- [ ] SHP / Google Earth / cloud sync
- [ ] Share image + QR

## Backend & Processing
- [x] Vulkan compute (fully plumbed)
- [ ] WebAssembly / server-side / Docker / CLI / batch

## Field Use Features
- [x] GPS tracking + auto observer placement
- [ ] AR view, offline maps, field notes, photos, voice memos

## UI/UX & QoL
- [ ] Dark mode / Material You / themes
- [ ] Parameter presets, searchable history, bulk ops

## Remaining items from original list are tracked below (status updated as implemented)

(Full original categorized list preserved from previous commit - all items remain planned for incremental addition.)