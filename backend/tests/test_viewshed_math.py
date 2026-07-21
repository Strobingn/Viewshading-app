"""Unit tests for continuous-horizon viewshed math (aligned with Android)."""
import math
import sys
from pathlib import Path

# Allow `python -m pytest` from repo root or backend/
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.viewshed import (
    clamp_refraction,
    compute_viewshed,
    elevation_angle_rad,
    effective_earth_radius_m,
    sample_ray,
)


def test_refraction_clamp():
    assert clamp_refraction(0.9) == 0.25
    assert clamp_refraction(-1.0) == 0.0
    assert clamp_refraction(0.13) == 0.13


def test_curvature_drop_matches_reff():
    d = 10_000.0
    k = 0.13
    r_eff = effective_earth_radius_m(k)
    drop = (d * d) / (2.0 * r_eff)
    alt = elevation_angle_rad(100.0, 100.0, d, True, k)
    assert abs(alt - math.atan2(-drop, d)) < 1e-12


def test_continuous_occlusion_stops_before_far_peak():
    """Near ridge blocks; far taller peak must not re-extend the ray."""
    origin_lat, origin_lon = 0.0, 0.0

    def elev(lat, lon):
        # Approximate northing meters from origin
        dy = (lat - origin_lat) * 110_540.0
        if 200.0 <= dy <= 350.0:
            return 40.0
        if 650.0 <= dy <= 800.0:
            return 80.0
        return 0.0

    last = sample_ray(
        observer_lat=origin_lat,
        observer_lon=origin_lon,
        bearing=0.0,
        eye_elev=1.0,
        max_distance_m=1000.0,
        samples_per_ray=40,
        use_curvature=False,
        refraction_coeff=0.13,
        elevation_fn=elev,
        target_height_m=0.0,
    )
    assert 150.0 <= last <= 450.0, f"expected stop near first ridge, got {last}"


def test_flat_full_range_no_curvature():
    last = sample_ray(
        observer_lat=41.5,
        observer_lon=-74.0,
        bearing=90.0,
        eye_elev=102.0,
        max_distance_m=1000.0,
        samples_per_ray=40,
        use_curvature=False,
        refraction_coeff=0.13,
        elevation_fn=lambda lat, lon: 100.0,
    )
    assert last > 950.0


def test_compute_viewshed_meta_aligned():
    fc = compute_viewshed(
        observer_lat=41.5,
        observer_lon=-74.0,
        eye_height_m=2.0,
        max_distance_m=1000.0,
        num_rays=24,
        samples_per_ray=20,
        use_curvature=False,
        elevation_fn=lambda lat, lon: 50.0,
    )
    assert fc["meta"]["algorithm"] == "radial-ray-march-continuous-horizon"
    assert fc["meta"]["aligned_with"] == "android-ViewshedEngine"
    assert len(fc["features"]) == 1
