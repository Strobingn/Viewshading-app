from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
import os
import shutil
import tempfile
from pathlib import Path

from .models import ViewshedRequest, ViewshedResponse
from .viewshed import compute_viewshed
from .terrain_api import (
    ElevationSampleRequest,
    TerrainAnalyzeRequest,
    analyze_terrain,
    sample_elevations,
)
from .collaboration import router as collaboration_router

app = FastAPI(
    title="Viewshade Shared Backend",
    description=(
        "Heavy DEM / viewshed for Viewshade + terrain analysis for Find It (metal). "
        "Same Oracle Cloud host serves both Android apps."
    ),
    version="0.2.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
app.include_router(collaboration_router)

UPLOAD_DIR = Path(
    os.getenv(
        "VIEWSHADE_UPLOAD_DIR",
        str(Path(tempfile.gettempdir()) / "viewshade-uploads"),
    )
)
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)


@app.get("/")
def root():
    return {
        "service": "viewshade-shared-backend",
        "status": "ok",
        "clients": ["viewshade", "find-it"],
        "endpoints": {
            "GET /health": "health check",
            "POST /viewshed": "Viewshade: LOS polygon (demo terrain)",
            "POST /viewshed/upload": "Viewshade: DEM upload + viewshed",
            "POST /elevation/sample": "Both: lat/lon → elevation_m[]",
            "POST /terrain/analyze": "Find It: hillshade / SVF / disturbance grid",
            "/collaboration/projects": "Self-hosted projects, versions, comments, and roles",
        },
    }


@app.get("/health")
def health():
    return {"status": "healthy", "service": "viewshade-shared-backend", "version": "0.2.0"}


@app.post("/elevation/sample")
def elevation_sample(req: ElevationSampleRequest):
    """Shared elevation sampling (Viewshade rays or Find It geotags)."""
    return sample_elevations(req)


@app.post("/terrain/analyze")
def terrain_analyze(req: TerrainAnalyzeRequest):
    """Find It metal: relief products for ground-feature hunting."""
    return analyze_terrain(req)


@app.post("/viewshed", response_model=ViewshedResponse)
def viewshed_json(req: ViewshedRequest):
    """
    Compute viewshed using the built-in demo terrain.
    Good for testing the Android client without a real DEM.
    """
    result = compute_viewshed(
        observer_lat=req.observer.lat,
        observer_lon=req.observer.lon,
        eye_height_m=req.observer.height_m,
        target_height_m=req.target_height_m,
        max_distance_m=req.max_distance_m,
        num_rays=req.num_rays,
        samples_per_ray=req.samples_per_ray,
        refraction_coeff=req.refraction_coeff,
        use_curvature=req.use_curvature,
    )
    return result


@app.post("/viewshed/upload")
async def viewshed_with_dem(
    file: UploadFile = File(...),
    lat: float = Form(...),
    lon: float = Form(...),
    height_m: float = Form(1.6),
    target_height_m: float = Form(0.0),
    max_distance_m: float = Form(5000.0),
    num_rays: int = Form(72),
    samples_per_ray: int = Form(80),
    refraction_coeff: float = Form(0.13),
    use_curvature: bool = Form(True),
):
    """Upload a georeferenced DEM and calculate against its actual elevation band."""
    suffix = os.path.splitext(file.filename or "")[1].lower()
    if suffix not in {".tif", ".tiff", ".asc", ".txt", ".xyz"}:
        raise HTTPException(400, "Supported formats: .tif, .tiff, .asc, .txt, .xyz")

    # Keep client filenames out of the filesystem path and avoid collisions.
    suffix = suffix if suffix else ".dem"
    temp_path: Path
    with tempfile.NamedTemporaryFile(
        mode="wb",
        suffix=suffix,
        prefix="dem_",
        dir=UPLOAD_DIR,
        delete=False,
    ) as f:
        temp_path = Path(f.name)
        copied = 0
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            copied += len(chunk)
            if copied > 1024 * 1024 * 1024:
                raise HTTPException(413, "DEM upload exceeds the 1 GiB service limit")
            f.write(chunk)

    try:
        import rasterio
        from rasterio.warp import transform

        with rasterio.open(temp_path) as dataset:
            if dataset.count < 1 or dataset.crs is None:
                raise HTTPException(422, "DEM must contain an elevation band and a defined CRS")

            def sample_dem(sample_lat: float, sample_lon: float) -> float:
                if dataset.crs.to_epsg() == 4326:
                    x, y = sample_lon, sample_lat
                else:
                    xs, ys = transform("EPSG:4326", dataset.crs, [sample_lon], [sample_lat])
                    x, y = xs[0], ys[0]
                if not (dataset.bounds.left <= x <= dataset.bounds.right and dataset.bounds.bottom <= y <= dataset.bounds.top):
                    raise ValueError(f"DEM does not cover {sample_lat:.6f}, {sample_lon:.6f}")
                value = float(next(dataset.sample([(x, y)], indexes=1))[0])
                if not value == value or (dataset.nodata is not None and abs(value - dataset.nodata) <= 1e-9):
                    raise ValueError(f"DEM contains nodata at {sample_lat:.6f}, {sample_lon:.6f}")
                return value

            try:
                result = compute_viewshed(
                    observer_lat=lat,
                    observer_lon=lon,
                    eye_height_m=height_m,
                    target_height_m=target_height_m,
                    max_distance_m=max_distance_m,
                    num_rays=num_rays,
                    samples_per_ray=samples_per_ray,
                    refraction_coeff=refraction_coeff,
                    use_curvature=use_curvature,
                    elevation_fn=sample_dem,
                )
            except ValueError as error:
                raise HTTPException(422, str(error)) from error
            result["meta"].update(
                {
                    "dem_file": file.filename,
                    "terrain": "uploaded-dem",
                    "crs": dataset.crs.to_string(),
                    "width": dataset.width,
                    "height": dataset.height,
                    "nodata": dataset.nodata,
                }
            )
            return JSONResponse(result)
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(422, f"Unable to read DEM: {error}") from error
    finally:
        temp_path.unlink(missing_ok=True)
