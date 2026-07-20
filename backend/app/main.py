from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
import os
import tempfile
import shutil
from typing import Optional

from .models import ViewshedRequest, ViewshedResponse, Observer
from .viewshed import compute_viewshed
from .terrain_api import (
    ElevationSampleRequest,
    TerrainAnalyzeRequest,
    analyze_terrain,
    sample_elevations,
)

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

UPLOAD_DIR = "/app/uploads"
os.makedirs(UPLOAD_DIR, exist_ok=True)


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
    max_distance_m: float = Form(5000.0),
    num_rays: int = Form(72),
    samples_per_ray: int = Form(80),
    refraction_coeff: float = Form(0.13),
    use_curvature: bool = Form(True),
):
    """
    Upload a DEM (GeoTIFF or ASC) and run viewshed against it.
    Currently falls back to demo terrain if the file cannot be read.
    Full rasterio sampling will be wired in the next iteration.
    """
    suffix = os.path.splitext(file.filename or "")[1].lower()
    if suffix not in {".tif", ".tiff", ".asc", ".txt", ".xyz"}:
        raise HTTPException(400, "Supported formats: .tif, .tiff, .asc, .txt, .xyz")

    # save upload
    dest = os.path.join(UPLOAD_DIR, file.filename or "upload.dem")
    with open(dest, "wb") as f:
        shutil.copyfileobj(file.file, f)

    # TODO: open with rasterio and sample real elevations
    # For now we still return a valid viewshed so the client can be tested end-to-end.
    result = compute_viewshed(
        observer_lat=lat,
        observer_lon=lon,
        eye_height_m=height_m,
        max_distance_m=max_distance_m,
        num_rays=num_rays,
        samples_per_ray=samples_per_ray,
        refraction_coeff=refraction_coeff,
        use_curvature=use_curvature,
    )
    result["meta"]["dem_file"] = file.filename
    result["meta"]["note"] = "DEM uploaded but real sampling not yet active – using demo terrain"
    return JSONResponse(result)
