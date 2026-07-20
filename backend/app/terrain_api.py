"""
Shared terrain helpers for Viewshade + Find It (metal) clients.

Uses demo elevation surface (same family as viewshed demo).
Real DEM/rasterio sampling plugs in later without changing routes.
"""

from __future__ import annotations

import math
from typing import List, Literal, Optional

import numpy as np
from pydantic import BaseModel, Field

from .viewshed import demo_elevation


class SamplePoint(BaseModel):
    lat: float
    lon: float


class ElevationSampleRequest(BaseModel):
    points: List[SamplePoint] = Field(..., min_length=1, max_length=500)


class ElevationSampleResponse(BaseModel):
    elevations_m: List[Optional[float]]
    source: str = "demo"


class TerrainAnalyzeRequest(BaseModel):
    """Grid around a center for hillshade / SVF / disturbance (Find It)."""
    center_lat: float = 41.503
    center_lon: float = -74.010
    half_size_m: float = Field(500.0, ge=50, le=5000)
    cell_size_m: float = Field(20.0, ge=5, le=200)
    mode: Literal["hillshade", "svf", "disturbance", "all"] = "all"


class TerrainAnalyzeResponse(BaseModel):
    width: int
    height: int
    west: float
    south: float
    east: float
    north: float
    cell_size_m: float
    # Row-major 0..1 floats as nested lists (small grids only)
    hillshade: Optional[List[List[float]]] = None
    svf: Optional[List[List[float]]] = None
    disturbance: Optional[List[List[float]]] = None
    source: str = "demo"


def _dest(lat: float, lon: float, bearing_deg: float, dist_m: float) -> tuple[float, float]:
    R = 6_371_000.0
    brng = math.radians(bearing_deg)
    lat1 = math.radians(lat)
    lon1 = math.radians(lon)
    lat2 = math.asin(
        math.sin(lat1) * math.cos(dist_m / R)
        + math.cos(lat1) * math.sin(dist_m / R) * math.cos(brng)
    )
    lon2 = lon1 + math.atan2(
        math.sin(brng) * math.sin(dist_m / R) * math.cos(lat1),
        math.cos(dist_m / R) - math.sin(lat1) * math.sin(lat2),
    )
    return math.degrees(lat2), math.degrees(lon2)


def sample_elevations(req: ElevationSampleRequest) -> ElevationSampleResponse:
    origin = req.points[0]
    elevs: List[Optional[float]] = []
    for p in req.points:
        elevs.append(float(demo_elevation(p.lat, p.lon, origin.lat, origin.lon)))
    return ElevationSampleResponse(elevations_m=elevs, source="demo")


def analyze_terrain(req: TerrainAnalyzeRequest) -> TerrainAnalyzeResponse:
    half = req.half_size_m
    cell = req.cell_size_m
    n = max(8, int(2 * half / cell))
    n = min(n, 128)  # cap payload size

    # Build lat/lon grid (row 0 = north)
    north_lat, _ = _dest(req.center_lat, req.center_lon, 0, half)
    south_lat, _ = _dest(req.center_lat, req.center_lon, 180, half)
    _, west_lon = _dest(req.center_lat, req.center_lon, 270, half)
    _, east_lon = _dest(req.center_lat, req.center_lon, 90, half)

    dem = np.zeros((n, n), dtype=np.float64)
    for r in range(n):
        lat = north_lat + (south_lat - north_lat) * (r / max(n - 1, 1))
        for c in range(n):
            lon = west_lon + (east_lon - west_lon) * (c / max(n - 1, 1))
            dem[r, c] = demo_elevation(lat, lon, req.center_lat, req.center_lon)

    def hillshade(z: np.ndarray) -> np.ndarray:
        # simple finite-difference shade
        dy, dx = np.gradient(z, cell)
        slope = np.arctan(np.hypot(dx, dy))
        aspect = np.arctan2(-dx, dy)
        az = math.radians(315.0)
        alt = math.radians(45.0)
        hs = np.sin(alt) * np.cos(slope) + np.cos(alt) * np.sin(slope) * np.cos(az - aspect)
        hs = np.clip(hs, 0, 1)
        return hs

    def svf_approx(z: np.ndarray, radius: int = 4) -> np.ndarray:
        out = np.ones_like(z)
        h, w = z.shape
        for y in range(radius, h - radius):
            for x in range(radius, w - radius):
                z0 = z[y, x]
                vis = 0
                tot = 0
                for dy in range(-radius, radius + 1):
                    for dx in range(-radius, radius + 1):
                        if dx * dx + dy * dy > radius * radius:
                            continue
                        tot += 1
                        if z[y + dy, x + dx] <= z0:
                            vis += 1
                out[y, x] = vis / max(tot, 1)
        return out

    def disturbance(z: np.ndarray, win: int = 5) -> np.ndarray:
        # residual vs local mean → normalize 0..1
        from numpy.lib.stride_tricks import sliding_window_view

        pad = win // 2
        zp = np.pad(z, pad, mode="edge")
        windows = sliding_window_view(zp, (win, win))
        mean = windows.mean(axis=(-1, -2))
        res = z - mean
        mx = np.max(np.abs(res)) or 1.0
        return np.clip(res / mx * 0.5 + 0.5, 0, 1)

    hs = svf = dist = None
    if req.mode in ("hillshade", "all"):
        hs = hillshade(dem).tolist()
    if req.mode in ("svf", "all"):
        svf = svf_approx(dem).tolist()
    if req.mode in ("disturbance", "all"):
        dist = disturbance(dem).tolist()

    return TerrainAnalyzeResponse(
        width=n,
        height=n,
        west=west_lon,
        south=south_lat,
        east=east_lon,
        north=north_lat,
        cell_size_m=cell,
        hillshade=hs,
        svf=svf,
        disturbance=dist,
        source="demo",
    )
