# Vulkan Compute Pipeline Integration for Viewshed (Sir)

## Overview
The slope/atan hot path in ray sampling can be offloaded to Vulkan Compute for 10-100x speedup on high ray counts.

## Files Added
- viewshed_compute.glsl (compute shader)
- ViewshedEngine.vulkanComputeViewshed() stub

## Full Integration Steps (Android 2026)

1. **Gradle / Native**
   - Enable NDK in app/build.gradle.kts
   - Add cmake or ndkBuild
   - Depend on Vulkan via `implementation 'androidx.vulkan:vulkan:1.3.+'` or direct NDK

2. **Native C++ (jni/)**
   - Create Vulkan instance, physical device, queue with compute support
   - Load SPIR-V from viewshed_compute.glsl (compile with glslc)
   - Create compute pipeline, descriptor sets, buffers
   - Map Kotlin GeoPoint arrays to VkBuffer
   - Dispatch with vkCmdDispatch
   - Read back visible points

3. **JNI Bridge**
   Example:
   extern "C" JNIEXPORT jobjectArray JNICALL
   Java_com_viewshed_app_viewshed_ViewshedEngine_nativeVulkanCompute(...)

4. **Shader Compilation**
   glslc viewshed_compute.glsl -o viewshed_compute.spv

5. **Fallback**
   Engine already falls back to highly optimized adaptive CPU path if Vulkan not available or init fails.

## Performance Target
- 72 rays x 40 samples = ~3k points -> sub 50ms on modern Adreno/Mali with Vulkan compute.

Contact Sir for full native implementation or handoff to native dev.
