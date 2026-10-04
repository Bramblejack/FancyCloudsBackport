#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

const int FLAG_MASK_DIR = 7;
const int FLAG_INSIDE_FACE = 1 << 4;
const int FLAG_USE_TOP_COLOR = 1 << 5;
const int FLAG_EXTRA_Z = 1 << 6;
const int FLAG_EXTRA_X = 1 << 7;

layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};

uniform isamplerBuffer CloudFaces;

out float vertexDistance;
out vec4 vertexColor;

// Four corners per face, grouped by direction: down, up, north, south, west, east.
const vec3[] corners = vec3[](
    vec3(1, 0, 0), vec3(1, 0, 1), vec3(0, 0, 1), vec3(0, 0, 0),
    vec3(0, 1, 0), vec3(0, 1, 1), vec3(1, 1, 1), vec3(1, 1, 0),
    vec3(0, 0, 0), vec3(0, 1, 0), vec3(1, 1, 0), vec3(1, 0, 0),
    vec3(1, 0, 1), vec3(1, 1, 1), vec3(0, 1, 1), vec3(0, 0, 1),
    vec3(0, 0, 1), vec3(0, 1, 1), vec3(0, 1, 0), vec3(0, 0, 0),
    vec3(1, 0, 0), vec3(1, 1, 0), vec3(1, 1, 1), vec3(1, 0, 1)
);

// Per-direction shading, in the same order. All faces share the cloud translucency of 0.8.
const vec4[] shading = vec4[](
    vec4(0.7, 0.7, 0.7, 0.8),
    vec4(1.0, 1.0, 1.0, 0.8),
    vec4(0.8, 0.8, 0.8, 0.8),
    vec4(0.8, 0.8, 0.8, 0.8),
    vec4(0.9, 0.9, 0.9, 0.8),
    vec4(0.9, 0.9, 0.9, 0.8)
);

void main() {
    int cornerIndex = gl_VertexID % 4;
    int record = (gl_VertexID / 4) * 3;

    int packedX = texelFetch(CloudFaces, record).r;
    int packedZ = texelFetch(CloudFaces, record + 1).r;
    int dirAndFlags = texelFetch(CloudFaces, record + 2).r;

    int direction = dirAndFlags & FLAG_MASK_DIR;
    bool insideFace = (dirAndFlags & FLAG_INSIDE_FACE) != 0;
    bool useTopColor = (dirAndFlags & FLAG_USE_TOP_COLOR) != 0;
    int cellX = (packedX << 1) | ((dirAndFlags & FLAG_EXTRA_X) >> 7);
    int cellZ = (packedZ << 1) | ((dirAndFlags & FLAG_EXTRA_Z) >> 6);

    vec3 corner = corners[direction * 4 + (insideFace ? 3 - cornerIndex : cornerIndex)];
    vec3 local = (corner + vec3(cellX, 0, cellZ)) * CellSize;
    vec3 pos = local + CloudOffset;

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_spherical_distance(pos);
    vertexColor = (useTopColor ? shading[1] : shading[direction]) * CloudColor;
}
