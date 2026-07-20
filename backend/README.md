# Viewshed Backend

FastAPI + Docker service for heavy / high-resolution viewshed processing.

## Quick start (Docker)

Docker Desktop must be **running**.

```powershell
cd F:\Viewshading-app\backend
docker compose up --build
```

## Quick start (Windows, no Docker)

```powershell
cd F:\Viewshading-app\backend
.\scripts\run-local.ps1
```

Service: **http://localhost:8000**

- Docs: http://localhost:8000/docs  
- Health: http://localhost:8000/health  

### Smoke test

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
# POST demo viewshed (Newburgh)
$body = '{"observer":{"lat":41.503,"lon":-74.010,"height_m":1.6},"max_distance_m":2000,"num_rays":36,"samples_per_ray":40}'
Invoke-RestMethod http://127.0.0.1:8000/viewshed -Method POST -Body $body -ContentType "application/json"
```

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

## Oracle Cloud (production / always-on)

Step-by-step: **[deploy/oci/README.md](./deploy/oci/README.md)**  
Checklist: **[deploy/oci/oci-checklist.md](./deploy/oci/oci-checklist.md)**  
VM setup script: **[deploy/oci/setup-on-vm.sh](./deploy/oci/setup-on-vm.sh)**

## Notes

- Currently uses a synthetic demo terrain so the Android client can be tested end-to-end immediately.
- Real GeoTIFF sampling with rasterio is the next step (Phase 2).
- Drop DEMs into the `data/` volume if you want them available inside the container.

## Android integration

Point the app at `http://<your-machine-ip>:8000` (local LAN) or  
`http://<oracle-public-ip>:8000` (OCI). HTTPS recommended before public production.
