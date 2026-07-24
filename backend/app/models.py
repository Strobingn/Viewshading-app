from pydantic import BaseModel, Field
from typing import Optional, List


class Observer(BaseModel):
    lat: float = Field(..., description="Observer latitude")
    lon: float = Field(..., description="Observer longitude")
    height_m: float = Field(1.6, description="Eye height above terrain in meters")


class ViewshedRequest(BaseModel):
    observer: Observer
    target_height_m: float = Field(0.0, ge=0.0, le=200.0)
    max_distance_m: float = Field(5000.0, ge=100, le=50000)
    num_rays: int = Field(72, ge=8, le=720)
    samples_per_ray: int = Field(80, ge=10, le=500)
    refraction_coeff: float = Field(0.13, ge=0.0, le=0.25)
    use_curvature: bool = True


class ViewshedResponse(BaseModel):
    type: str = "FeatureCollection"
    features: List[dict]
    meta: dict
