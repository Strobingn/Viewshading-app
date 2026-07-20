#version 450

layout(local_size_x = 16, local_size_y = 16) in;

layout(set = 0, binding = 0) readonly buffer InputElevations {
    float elevations[];
};

layout(set = 0, binding = 1) readonly buffer GridParams {
    int width;
    int height;
    float cellSize;
    float azimuth;
    float altitude;
};

layout(set = 0, binding = 2) buffer OutputHillshade {
    float hillshade[];
};

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= width || pos.y >= height) return;

    int idx = pos.y * width + pos.x;
    float z = elevations[idx];

    if (z == 0.0) { // NaN placeholder
        hillshade[idx] = 0.0;
        return;
    }

    // Sobel gradients (simplified for compute shader)
    float dzdx = (elevations[pos.y * width + min(pos.x + 1, width-1)] - elevations[pos.y * width + max(pos.x - 1, 0)]) / (2.0 * cellSize);
    float dzdy = (elevations[min(pos.y + 1, height-1) * width + pos.x] - elevations[max(pos.y - 1, 0) * width + pos.x]) / (2.0 * cellSize);

    float slope = atan(sqrt(dzdx * dzdx + dzdy * dzdy));
    float aspect = atan2(dzdy, -dzdx);

    float az = radians(azimuth);
    float al = radians(altitude);

    float shade = cos(al) * cos(slope) + sin(al) * sin(slope) * cos(az - aspect);
    hillshade[idx] = clamp(shade, 0.0, 1.0);
}
