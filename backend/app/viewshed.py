"""
Simple radial ray-marching viewshed.
Works with a flat elevation grid or a synthetic demo surface.
Designed to be swapped later for a full Wang / R2 / GPU implementation.
"""

from __future__ import annotations
import math
from typing import List, Tuple, Optional
import numpy as np
from shapely.geometry import Polygon, mapping
from shapely.ops import unary_union


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def destination_point(lat: float, lon: float, bearing_deg: float, distance_m: float) -> Tuple[float, float]:
    R = 6371000.0
    brng = math.radians(bearing_deg)
    lat1 = math.radians(lat)
    lon1 = math.radians(lon)
    lat2 = math.asin(
        math.sin(lat1) * math.cos(distance_m / R) +
        math.cos(lat1) * math.sin(distance_m / R) * math.cos(brng)
    )
    lon2 = lon1 + math.atan2(
        math.sin(brng) * math.sin(distance_m / R) * math.cos(lat1),
        math.cos(distance_m / R) - math.sin(lat1) * math.sin(lat2)
    )
    return math.degrees(lat2), math.degrees(lon2)


def demo_elevation(lat: float, lon: float, origin_lat: float, origin_lon: float) -> float:
    """Simple synthetic terrain centered on the observer (good for testing)."""
    dx = (lon - origin_lon) * 111320 * math.cos(math.radians(origin_lat))
    dy = (lat - origin_lat) * 110540
    dist = math.hypot(dx, dy)
    # rolling hills + a ridge
    base = 40.0 + 18.0 * math.sin(dx * 0.0008) * math.cos(dy * 0.0007)
    ridge = 25.0 * math.exp(-((dx - 800) ** 2 + (dy - 300) ** 2) / 1.2e6)
    return base + ridge


def compute_viewshed(
    observer_lat: float,
    observer_lon: float,
    eye_height_m: float = 1.6,
    max_distance_m: float = 5000.0,
    num_rays: int = 72,
    samples_per_ray: int = 80,
    refraction_coeff: float = 0.13,
    use_curvature: bool = True,
    elevation_fn=None,
) -> dict:
    """
    Returns a GeoJSON FeatureCollection with the visible polygon.
    elevation_fn(lat, lon) -> elevation meters. If None, uses demo terrain.
    """
    if elevation_fn is None:
        elevation_fn = lambda lat, lon: demo_elevation(lat, lon, observer_lat, observer_lon)

    observer_elev = elevation_fn(observer_lat, observer_lon) + eye_height_m
    angle_step = 360.0 / num_rays
    dist_step = max_distance_m / samples_per_ray

    visible_points: List[Tuple[float, float]] = []

    for r in range(num_rays):
        bearing = r * angle_step
        max_slope = -math.inf
        last_visible = None

        for s in range(1, samples_per_ray + 1):
            d = s * dist_step
            lat, lon = destination_point(observer_lat, observer_lon, bearing, d)
            elev = elevation_fn(lat, lon)

            # Earth curvature + refraction correction
            if use_curvature:
                # approximate drop: d² / (2R) adjusted by refraction
                R = 6371000.0
                curve = (d * d) / (2.0 * R) * (1.0 - refraction_coeff)
                elev_corrected = elev - curve
            else:
                elev_corrected = elev

            slope = math.atan2(elev_corrected - observer_elev, d)

            if slope >= max_slope:
                max_slope = slope
                last_visible = (lon, lat)  # GeoJSON is lon,lat

        if last_visible:
            visible_points.append(last_visible)

    if len(visible_points) < 3:
        # fallback tiny circle so the client always gets something
        ring = []
        for i in range(36):
            b = i * 10
            la, lo = destination_point(observer_lat, observer_lon, b, 200)
            ring.append((lo, la))
        ring.append(ring[0])
        poly = Polygon(ring)
    else:
        # close the ring
        ring = visible_points + [visible_points[0]]
        poly = Polygon(ring)
        # optional: simplify a bit
        poly = poly.simplify(0.00005, preserve_topology=True)

    feature = {
        "type": "Feature",
        "geometry": mapping(poly),
        "properties": {
            "observer_lat": observer_lat,
            "observer_lon": observer_lon,
            "eye_height_m": eye_height_m,
            "max_distance_m": max_distance_m,
            "num_rays": num_rays,
        },
    }

    return {
        "type": "FeatureCollection",
        "features": [feature],
        "meta": {
            "algorithm": "radial-ray-march",
            "rays": num_rays,
            "samples_per_ray": samples_per_ray,
            "curvature": use_curvature,
            "refraction": refraction_coeff,
        },
    }
