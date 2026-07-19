#version 450

// Vulkan Compute Shader for Viewshed slope calculation (Sir request)
// Hot loop: per-sample elevation -> slope -> max slope reduction

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(set = 0, binding = 0) readonly buffer InputElevations {
    float elevations[];
};

layout(set = 0, binding = 1) readonly buffer InputDistances {
    float distances[];
};

layout(set = 0, binding = 2) readonly buffer InputObserverHeight {
    float observerHeight;
};

layout(set = 0, binding = 3) buffer OutputVisible {
    uint visibleCount;
    float visibleDistances[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= distances.length()) return;

    float h = elevations[idx];
    float d = distances[idx];
    float slope = atan((h - observerHeight) / d);

    // Simple reduction example (in real code use atomicMax or subgroup ops)
    if (slope > 0.0) {  // visible
        // atomic add or write to output
        visibleDistances[atomicAdd(visibleCount, 1)] = d;
    }
}
