# Vulkan Compute Shaders for Viewshed Ray Sampling - Deep Exploration (Sir)

## Why Vulkan Compute for this workload?
The per-sample slope = atan((h - observerH) / d) + max-slope reduction is embarrassingly parallel across thousands of samples.
Mobile GPUs (Adreno 7xx / Mali-G7xx) excel at this when memory access is coalesced and we minimize atomics.

## Advanced Shader Features Used
- Subgroup arithmetic (GL_KHR_shader_subgroup_arithmetic) for fast max reduction inside warp/wave without atomics.
- SOA (Structure of Arrays) layout for coalesced global memory reads.
- Shared memory + barrier for workgroup reduction.
- local_size_x = 64 tuned for common mobile subgroup size.

## Recommended Dispatch Strategy
Dispatch as 2D: x = samplesPerRay, y = numRays
One workgroup per ray or per tile of rays.

## Full Host-Side Steps (more detail)
1. Compile shader to SPIR-V with glslc --target-env=vulkan1.3
2. Create VkShaderModule
3. Descriptor set layout with 4 storage buffers + uniform buffer for params
4. Pipeline with VK_PIPELINE_BIND_POINT_COMPUTE
5. Command buffer: bind pipeline, bind descriptors, vkCmdDispatch(numRays, 1, 1) or 2D
6. Memory barrier + vkCmdCopyBuffer to read back visible horizons

## Mobile Gotchas
- Tile-based GPUs love small working sets. Keep samplesPerRay * numRays under ~8k-16k for good cache behavior.
- Prefer subgroup ops over atomics when possible.
- Use push constants for observerHeight instead of uniform buffer when possible.

## Next Level
- Multi-pass: first pass finds per-ray max slope, second pass collects visible points.
- Bindless or indirect dispatch for variable ray counts.
- Combine with async compute queue to overlap with graphics (map rendering).

Current simple shader is good starting point. Advanced version (viewshed_compute_advanced.glsl) demonstrates subgroup + shared memory reduction.

Ready for full native C++ implementation when needed, Sir.