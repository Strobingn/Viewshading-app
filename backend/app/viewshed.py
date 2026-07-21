"""Deterministic radial viewshed calculation shared by the Android client."""

from __future__ import annotations

import math
from typing import Callable, List, Tuple


EARTH_RADIUS_M = 6_371_000.0
ANGLE_EPSILON = 1e-12
ElevationFn = Callable[[float, float], float]


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = (
        math.sin(dphi / 2) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    )
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def destination_point(
    lat: float,
    lon: float,
    bearing_deg: float,
    distance_m: float,
) -> Tuple[float, float]:
    brng = math.radians(bearing_deg)
    lat1 = math.radians(lat)
    lon1 = math.radians(lon)
    lat2 = math.asin(
        math.sin(lat1) * math.cos(distance_m / EARTH_RADIUS_M)
        + math.cos(lat1)
        * math.sin(distance_m / EARTH_RADIUS_M)
        * math.cos(brng)
    )
    lon2 = lon1 + math.atan2(
        math.sin(brng)
        * math.sin(distance_m / EARTH_RADIUS_M)
        * math.cos(lat1),
        math.cos(distance_m / EARTH_RADIUS_M) - math.sin(lat1) * math.sin(lat2),
    )
    normalized_lon = (math.degrees(lon2) + 540.0) % 360.0 - 180.0
    return math.degrees(lat2), normalized_lon


def demo_elevation(
    lat: float,
    lon: float,
    origin_lat: float,
    origin_lon: float,
) -> float:
    """Synthetic terrain used only by the explicitly labelled demo endpoint."""
    dx = (lon - origin_lon) * 111_320 * math.cos(math.radians(origin_lat))
    dy = (lat - origin_lat) * 110_540
    base = 40.0 + 18.0 * math.sin(dx * 0.0008) * math.cos(dy * 0.0007)
    ridge = 25.0 * math.exp(-((dx - 800) ** 2 + (dy - 300) ** 2) / 1.2e6)
    return base + ridge


def _elevation_angle(
    observer_elev_m: float,
    target_elev_m: float,
    distance_m: float,
    use_curvature: bool,
    refraction_coeff: float,
) -> float:
    corrected_target = target_elev_m
    if use_curvature:
        corrected_target -= (
            distance_m * distance_m / (2.0 * EARTH_RADIUS_M)
        ) * (1.0 - refraction_coeff)
    return math.atan2(corrected_target - observer_elev_m, distance_m)


def _sector_ring(
    observer_lat: float,
    observer_lon: float,
    bearing_start_deg: float,
    bearing_end_deg: float,
    inner_distance_m: float,
    outer_distance_m: float,
) -> List[List[float]]:
    outer_start = destination_point(
        observer_lat, observer_lon, bearing_start_deg, outer_distance_m
    )
    outer_end = destination_point(
        observer_lat, observer_lon, bearing_end_deg, outer_distance_m
    )
    if inner_distance_m <= 0.0:
        return [
            [observer_lon, observer_lat],
            [outer_start[1], outer_start[0]],
            [outer_end[1], outer_end[0]],
            [observer_lon, observer_lat],
        ]

    inner_start = destination_point(
        observer_lat, observer_lon, bearing_start_deg, inner_distance_m
    )
    inner_end = destination_point(
        observer_lat, observer_lon, bearing_end_deg, inner_distance_m
    )
    return [
        [inner_start[1], inner_start[0]],
        [outer_start[1], outer_start[0]],
        [outer_end[1], outer_end[0]],
        [inner_end[1], inner_end[0]],
        [inner_start[1], inner_start[0]],
    ]


def compute_viewshed(
    observer_lat: float,
    observer_lon: float,
    eye_height_m: float = 1.6,
    target_height_m: float = 0.0,
    max_distance_m: float = 5000.0,
    num_rays: int = 72,
    samples_per_ray: int = 80,
    refraction_coeff: float = 0.13,
    use_curvature: bool = True,
    elevation_fn: ElevationFn | None = None,
) -> dict:
    """Return visible radial cells as a GeoJSON MultiPolygon.

    Every sample is tested against the terrain horizon. Target height applies only
    to the candidate target, never to terrain that can block later samples. This
    preserves a visible far peak beyond a hidden valley instead of filling the
    entire ray to its farthest visible point.
    """
    using_demo_terrain = elevation_fn is None
    if elevation_fn is None:
        elevation_fn = lambda lat, lon: demo_elevation(
            lat, lon, observer_lat, observer_lon
        )

    ray_count = max(8, min(int(num_rays), 720))
    sample_count = max(10, min(int(samples_per_ray), 500))
    max_distance = max(100.0, min(float(max_distance_m), 50_000.0))
    refraction = max(0.0, min(float(refraction_coeff), 0.99))
    eye_height = max(0.0, min(float(eye_height_m), 100.0))
    target_height = max(0.0, min(float(target_height_m), 200.0))

    observer_elev = elevation_fn(observer_lat, observer_lon) + eye_height
    bearing_width = 360.0 / ray_count
    half_bearing_width = bearing_width / 2.0
    distance_step = max_distance / sample_count

    ranges_m: List[float] = []
    sector_meta: List[dict] = []
    polygons: List[List[List[List[float]]]] = []
    visible_cells = 0
    visible_area_m2 = 0.0

    for ray_index in range(ray_count):
        bearing = ray_index * bearing_width
        terrain_horizon = -math.inf
        visibility: List[bool] = []
        farthest_visible = 0.0

        for sample_index in range(1, sample_count + 1):
            distance = sample_index * distance_step
            lat, lon = destination_point(
                observer_lat, observer_lon, bearing, distance
            )
            ground = float(elevation_fn(lat, lon))
            terrain_angle = _elevation_angle(
                observer_elev,
                ground,
                distance,
                use_curvature,
                refraction,
            )
            target_angle = _elevation_angle(
                observer_elev,
                ground + target_height,
                distance,
                use_curvature,
                refraction,
            )
            is_visible = target_angle >= terrain_horizon - ANGLE_EPSILON
            visibility.append(is_visible)
            if is_visible:
                farthest_visible = distance
            terrain_horizon = max(terrain_horizon, terrain_angle)

        ranges_m.append(farthest_visible)

        run_start = -1
        for index in range(sample_count + 1):
            is_visible = index < sample_count and visibility[index]
            if is_visible and run_start < 0:
                run_start = index
            if not is_visible and run_start >= 0:
                inner_distance = run_start * distance_step
                outer_distance = index * distance_step
                cell_count = index - run_start
                start_bearing = (bearing - half_bearing_width) % 360.0
                end_bearing = (bearing + half_bearing_width) % 360.0
                area_m2 = 0.5 * (
                    outer_distance * outer_distance
                    - inner_distance * inner_distance
                ) * math.radians(bearing_width)
                ring = _sector_ring(
                    observer_lat,
                    observer_lon,
                    start_bearing,
                    end_bearing,
                    inner_distance,
                    outer_distance,
                )
                polygons.append([ring])
                sector_meta.append(
                    {
                        "ray_index": ray_index,
                        "bearing_start_deg": start_bearing,
                        "bearing_end_deg": end_bearing,
                        "inner_distance_m": inner_distance,
                        "outer_distance_m": outer_distance,
                        "visible_cell_count": cell_count,
                        "area_m2": area_m2,
                    }
                )
                visible_cells += cell_count
                visible_area_m2 += area_m2
                run_start = -1

    geometry = {"type": "MultiPolygon", "coordinates": polygons}
    feature = {
        "type": "Feature",
        "geometry": geometry,
        "properties": {
            "observer_lat": observer_lat,
            "observer_lon": observer_lon,
            "eye_height_m": eye_height,
            "target_height_m": target_height,
            "max_distance_m": max_distance,
            "num_rays": ray_count,
            "samples_per_ray": sample_count,
            "ranges_m": ranges_m,
            "sectors": sector_meta,
            "visible_cells": visible_cells,
            "total_cells": ray_count * sample_count,
            "visible_area_km2": visible_area_m2 / 1_000_000.0,
        },
    }

    return {
        "type": "FeatureCollection",
        "features": [feature],
        "meta": {
            "algorithm": "radial-terrain-horizon-cells",
            "rays": ray_count,
            "samples_per_ray": sample_count,
            "curvature": use_curvature,
            "refraction": refraction,
            "terrain": "demo" if using_demo_terrain else "custom",
        },
    }
