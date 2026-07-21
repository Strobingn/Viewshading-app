"""
Radial ray-marching viewshed (max elevation-angle, first continuous occlusion).

Aligned with the Android ViewshedEngine:
- Cast N rays around the observer
- Along each ray raise the horizon when elevation angle increases
- Stop at the first sample that does not raise the horizon (continuous LOS)
- Far peaks do not reappear after a nearer ridge blocks

Designed to stay in sync with app/src/.../ViewshedEngine.kt.
"""

from __future__ import annotations
import math
from typing import List, Tuple, Optional, Callable


EARTH_RADIUS_M = 6_371_000.0
MIN_REFRACTION = 0.0
MAX_REFRACTION = 0.25
DEFAULT_REFRACTION = 0.13
ANGLE_EPS = 1e-10


def clamp_refraction(k: float) -> float:
    return max(MIN_REFRACTION, min(MAX_REFRACTION, float(k)))


def effective_earth_radius_m(refraction_coeff: float) -> float:
    k = clamp_refraction(refraction_coeff)
    return EARTH_RADIUS_M / (1.0 - k)


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = EARTH_RADIUS_M
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * R * math.asin(math.sqrt(min(1.0, max(0.0, a))))


def destination_point(lat: float, lon: float, bearing_deg: float, distance_m: float) -> Tuple[float, float]:
    if distance_m <= 0.0:
        return lat, lon
    R = EARTH_RADIUS_M
    brng = math.radians(bearing_deg)
    lat1 = math.radians(lat)
    lon1 = math.radians(lon)
    d = distance_m / R
    lat2 = math.asin(
        math.sin(lat1) * math.cos(d) +
        math.cos(lat1) * math.sin(d) * math.cos(brng)
    )
    lon2 = lon1 + math.atan2(
        math.sin(brng) * math.sin(d) * math.cos(lat1),
        math.cos(d) - math.sin(lat1) * math.sin(lat2)
    )
    return math.degrees(lat2), math.degrees(lon2)


def elevation_angle_rad(
    observer_elev: float,
    target_elev: float,
    dist_m: float,
    use_curvature: bool,
    refraction_coeff: float,
) -> float:
    """Match Android GeoMath.elevationAngleRad."""
    if dist_m <= 0.0:
        return 0.0
    if use_curvature:
        r_eff = effective_earth_radius_m(refraction_coeff)
        drop = (dist_m * dist_m) / (2.0 * r_eff)
        delta_h = (target_elev - observer_elev) - drop
    else:
        delta_h = target_elev - observer_elev
    return math.atan2(delta_h, dist_m)


def demo_elevation(lat: float, lon: float, origin_lat: float, origin_lon: float) -> float:
    """Simple synthetic terrain centered on the observer (good for testing)."""
    dx = (lon - origin_lon) * 111320 * math.cos(math.radians(origin_lat))
    dy = (lat - origin_lat) * 110540
    # rolling hills + a ridge
    base = 40.0 + 18.0 * math.sin(dx * 0.0008) * math.cos(dy * 0.0007)
    ridge = 25.0 * math.exp(-((dx - 800) ** 2 + (dy - 300) ** 2) / 1.2e6)
    return base + ridge


def sample_ray(
    observer_lat: float,
    observer_lon: float,
    bearing: float,
    eye_elev: float,
    max_distance_m: float,
    samples_per_ray: int,
    use_curvature: bool,
    refraction_coeff: float,
    elevation_fn: Callable[[float, float], float],
    target_height_m: float = 0.0,
) -> float:
    """
    Continuous max-elevation-angle march.
    Returns last visible distance along the ray (meters).
    """
    horizon = -math.inf
    last_visible = 0.0
    samples = max(1, samples_per_ray)
    k = clamp_refraction(refraction_coeff)

    for s in range(1, samples + 1):
        d = s * max_distance_m / samples
        lat, lon = destination_point(observer_lat, observer_lon, bearing, d)
        ground = elevation_fn(lat, lon)
        target_elev = ground + target_height_m
        angle = elevation_angle_rad(eye_elev, target_elev, d, use_curvature, k)
        if angle > horizon + ANGLE_EPS:
            horizon = angle
            last_visible = d
        else:
            # First continuous occlusion — stop (do not reappear over far peaks)
            return last_visible
    return last_visible


def compute_viewshed(
    observer_lat: float,
    observer_lon: float,
    eye_height_m: float = 1.6,
    max_distance_m: float = 5000.0,
    num_rays: int = 72,
    samples_per_ray: int = 80,
    refraction_coeff: float = DEFAULT_REFRACTION,
    use_curvature: bool = True,
    elevation_fn=None,
    target_height_m: float = 0.0,
) -> dict:
    """
    Returns a GeoJSON FeatureCollection with the visible polygon.
    elevation_fn(lat, lon) -> elevation meters. If None, uses demo terrain.
    """
    # Lazy import so pure math helpers can be unit-tested without shapely installed.
    from shapely.geometry import Polygon, mapping

    if elevation_fn is None:
        elevation_fn = lambda lat, lon: demo_elevation(lat, lon, observer_lat, observer_lon)

    k = clamp_refraction(refraction_coeff)
    observer_ground = elevation_fn(observer_lat, observer_lon)
    observer_elev = observer_ground + eye_height_m
    angle_step = 360.0 / max(1, num_rays)

    ranges_m: List[float] = []
    visible_points: List[Tuple[float, float]] = []

    for r in range(num_rays):
        bearing = r * angle_step
        last_visible = sample_ray(
            observer_lat=observer_lat,
            observer_lon=observer_lon,
            bearing=bearing,
            eye_elev=observer_elev,
            max_distance_m=max_distance_m,
            samples_per_ray=samples_per_ray,
            use_curvature=use_curvature,
            refraction_coeff=k,
            elevation_fn=elevation_fn,
            target_height_m=target_height_m,
        )
        ranges_m.append(last_visible)
        if last_visible > 1.0:
            lat, lon = destination_point(observer_lat, observer_lon, bearing, last_visible)
        else:
            # Tiny offset so polygon is not degenerate when range ~ 0
            lat, lon = destination_point(observer_lat, observer_lon, bearing, 1.0)
        visible_points.append((lon, lat))  # GeoJSON is lon,lat

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
        ring = visible_points + [visible_points[0]]
        poly = Polygon(ring)
        poly = poly.simplify(0.00005, preserve_topology=True)

    positive = [d for d in ranges_m if d > 0.0]
    feature = {
        "type": "Feature",
        "geometry": mapping(poly),
        "properties": {
            "observer_lat": observer_lat,
            "observer_lon": observer_lon,
            "eye_height_m": eye_height_m,
            "max_distance_m": max_distance_m,
            "num_rays": num_rays,
            "max_range_m": max(ranges_m) if ranges_m else 0.0,
            "avg_range_m": (sum(positive) / len(positive)) if positive else 0.0,
        },
    }

    return {
        "type": "FeatureCollection",
        "features": [feature],
        "meta": {
            "algorithm": "radial-ray-march-continuous-horizon",
            "rays": num_rays,
            "samples_per_ray": samples_per_ray,
            "curvature": use_curvature,
            "refraction": k,
            "aligned_with": "android-ViewshedEngine",
        },
    }
