# Viewshed Backend

FastAPI + Docker service for heavy / high-resolution viewshed processing.

## Quick start

```bash
cd backend
docker compose up --build
```

Service will be available at **http://localhost:8000**

- Docs: http://localhost:8000/docs
- Health: http://localhost:8000/health

## Endpoints

### `POST /viewshed`
JSON body:

```json
{
  "observer": { "lat": 41.503, "lon": -74.010, "height_m": 1.6 },
  "max_distance_m": 5000,
  "num_rays": 72,
  "samples_per_ray": 80,
  "refraction_coeff": 0.13,
  "use_curvature": true
}
```

Returns a GeoJSON FeatureCollection with the visible polygon.

### `POST /viewshed/upload`
Multipart form:
- `file` – GeoTIFF / ASC / XYZ
- `lat`, `lon`, `height_m`, … (same params as above)

## Notes

- Currently uses a synthetic demo terrain so the Android client can be tested end-to-end immediately.
- Real GeoTIFF sampling with rasterio is the next step (Phase 2).
- Drop DEMs into the `data/` volume if you want them available inside the container.

## Android integration

Point the app at `http://<your-machine-ip>:8000` (or use a tunnel / production host).
