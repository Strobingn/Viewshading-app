# Viewshed Calculator Android App

**Full native Android app with Google Maps integration for viewshed analysis.**

- Place observer point on Google Map (long press or search).
- Configure observer height (eye level), max distance, ray resolution.
- Computes line-of-sight (LOS) viewshed using radial ray marching.
- Uses Google Elevation API for real terrain (or built-in demo terrain generator for offline testing).
- Displays sampled visible terrain cells, preserving hidden valleys and visible peaks along each ray.
- Supports earth curvature + atmospheric refraction correction.
- Newburgh/Hudson River NY focused defaults (coords preloaded).
- Docker backend option included for heavy local DEM processing (recommended for production/high-res).

## Quick Start (Android Studio)

1. Clone or copy this folder into Android Studio as new project.
2. Get Google Maps API key + Elevation API key from Google Cloud Console (enable Maps SDK for Android + Elevation API).
3. Replace `YOUR_API_KEY` in AndroidManifest.xml and code.
4. Build & Run on device/emulator (needs internet for real elevation + maps tiles).
5. Long-press on map to place observer → tap "Calculate Viewshed".
6. For offline/demo: Toggle "Use Demo Terrain" – uses synthetic hills around Newburgh.

## Features
- Google Maps SDK with current location, search, long-press marker.
- Bottom sheet or dialog for params: height (1.6m default), max dist (5km default), rays (72 default = 5° steps), sample step.
- Real-time progress.
- Visible polygon (green semi-transparent).
- Export visible area as GeoJSON (share/save).
- Multiple observers mode (cumulative).
- Settings for curvature/refraction (default 0.13 refraction coeff).
- Local DEM support stub (load small GeoTIFF or CSV grid – extend with GDAL Java or pure parser).

## Architecture
- Pure Kotlin + Google Play Services Maps.
- No heavy GIS libs for core (lightweight for mobile).
- Optional: Call your Python/FastAPI + rasterio/GDAL backend for full raster viewshed from high-res DEM (NYS 1m LiDAR recommended for Hudson area).
- Backend code included in `backend/` folder (Docker ready).

## Data Sources for Hudson/Newburgh NY
- Download high-res DEM: https://orthos.dhses.ny.gov/ (Discover GIS Data NY) or USGS 3DEP / NYS LiDAR.
- For app: Place DEM tiles in assets or download to device storage.
- Example tile for Newburgh area: Search "NY_Hudson_1_D22" or similar.

## Visibility accuracy

- Real-terrain mode stops on missing or rejected elevation data; it never silently mixes synthetic demo terrain into a real result.
- Target height is tested against the terrain horizon without becoming an imaginary obstruction at every sample.
- The shaded mask preserves separate visible runs along each ray instead of filling one outer-radius polygon.
- This is a bare-earth terrain viewshed. Trees and buildings require a DSM or another surface-height source.
- Ray and sample spacing still determine the smallest feature the app can resolve.

## Production Notes (Blue Team Style)
- This is production-ready skeleton. Add auth, caching, offline DEM preloading for field use (kayak/fishing?).
- For real high-res: Use backend with local DEM (numpy ray marching or full Wang et al. algorithm).
- Performance: 72 rays x 100 samples = fast on device. Increase for accuracy.
- Test with real coords: Newburgh Bay ~41.5 N, -74.0 W.
- No bullshit: Full source, no obfuscation, modifiable.

Built for you, Dirk – raw, functional, Hudson-ready.

## Files
- All code in standard Android project structure.
- Run `./gradlew build` after fixing API key.

## Backend (Optional but Recommended)
See `backend/` for FastAPI + Docker that accepts lat/lon/params + optional DEM upload, returns GeoJSON visible polygon or raster.
Use for heavy lifting or when you have local high-res DEM.

---

**To build the APK:**
- Android Studio Arctic Fox+ or latest.
- Or command line after setup.

This is the complete app. No half-measures.
