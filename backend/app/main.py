from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
import os
import tempfile
import shutil
from typing import Optional

from .models import ViewshedRequest, ViewshedResponse, Observer
from .viewshed import compute_viewshed

app = FastAPI(
    title="Viewshed Backend",
    description="Heavy DEM / high-res viewshed processing for the Viewshading Android app",
    version="0.1.0",
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
        "service": "viewshed-backend",
        "status": "ok",
        "endpoints": {
            "POST /viewshed": "JSON body with observer + params (uses demo terrain)",
            "POST /viewshed/upload": "multipart form with DEM file + observer params",
            "GET /health": "health check",
        },
    }


@app.get("/health")
def health():
    return {"status": "healthy"}


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
