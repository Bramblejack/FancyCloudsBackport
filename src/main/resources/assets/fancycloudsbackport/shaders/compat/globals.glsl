// Compat replacement for the 1.21 <minecraft:globals.glsl> include.

layout(std140) uniform Globals {
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
};
