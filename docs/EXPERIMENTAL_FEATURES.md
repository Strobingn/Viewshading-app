# Experimental features (fixed 2026-07-20)

## Working (CPU engine)

| Feature | Where | Notes |
|--------|--------|------|
| Parallel rays | Params / switch | Coroutine per-ray on `Dispatchers.Default` |
| Adaptive sampling | Switch | Denser steps when slope/angle changes |
| Binary-search horizon | Switch | Refines edge after linear/adaptive pass |
| Quality presets | Fast / Balanced / Accurate chips | Sets rays × samples |
| Analysis session save | Auto after calculate | `filesDir/last_session.json` |
| Horizon line helper | `HorizonLineCalculator` | High-quality outer ring |

## Optional native (does not block build)

- `app/src/main/cpp/viewshed_vulkan.cpp` — experimental Vulkan bridge
- `VulkanViewshed` loads library only if present; **never** crashes on missing NDK
- Default Gradle build does **not** require NDK/CMake

Enable native later with a dedicated product flavor or `-PenableVulkanNative=true` (wire when ready).

## Not merged from broken roadmap branch

LiDAR / ARCore / metal-detector scaffolding on `feature/complete-high-impact-roadmap` was largely incomplete and broke builds. Kept on that branch for reference; not in `main`.
