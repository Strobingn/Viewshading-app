#version 450
#extension GL_KHR_shader_subgroup_arithmetic : enable

// Advanced Vulkan Compute Shader for Viewshed (Sir exploration)
// Optimized for mobile GPUs (Adreno/Mali tile-based)

layout(local_size_x = 64) in;

// Better memory layout: SOA for coalesced access
layout(set = 0, binding = 0) readonly buffer Elevations {
    float elevations[];
};
layout(set = 0, binding = 1) readonly buffer Distances {
    float distances[];
};
layout(set = 0, binding = 2) readonly buffer Params {
    float observerHeight;
    uint rays;
    uint samplesPerRay;
};

layout(set = 0, binding = 3) buffer VisibleHorizons {
    float maxSlopePerRay[];
    uint visiblePointCount;
    float visibleDistances[];
};

shared float localMaxSlope[64];

void main() {
    uint rayId = gl_GlobalInvocationID.y;
    uint sampleId = gl_GlobalInvocationID.x;
    uint idx = rayId * Params.samplesPerRay + sampleId;

    if (sampleId >= Params.samplesPerRay || rayId >= Params.rays) return;

    float h = elevations[idx];
    float d = distances[idx];
    float slope = atan((h - Params.observerHeight) / max(d, 1.0));

    // Subgroup reduction for max slope within workgroup
    float maxInSubgroup = subgroupMax(slope);

    if (subgroupElect()) {
        // One thread per subgroup writes
        atomicMax(localMaxSlope[rayId % 64], maxInSubgroup); // simplistic
    }

    barrier();

    if (gl_LocalInvocationID.x == 0) {
        // Workgroup leader reduces and writes global
        if (localMaxSlope[rayId % 64] > maxSlopePerRay[rayId]) {
            maxSlopePerRay[rayId] = localMaxSlope[rayId % 64];
        }
    }
}
