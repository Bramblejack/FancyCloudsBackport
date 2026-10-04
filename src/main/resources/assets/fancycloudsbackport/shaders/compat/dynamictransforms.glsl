// Compat replacement for the 1.21 <minecraft:dynamictransforms.glsl> include.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
