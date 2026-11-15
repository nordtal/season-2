#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla 26.3 core/item.fsh plus the Nordtal border wall (look A).
// A fragment whose texel carries the marker colour is drawn as the wall's field; every other
// fragment takes the vanilla path, unchanged. Every Nordtal line is marked "nordtal".

#include <minecraft:globals.glsl> // nordtal: always, for GameTime (vanilla includes it for GLINT only)
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif
layout(location = 2) in vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec4 overlayColor;
#endif
layout(location = 5) in vec2 texCoord0;
#ifdef GLINT
layout(location = 6) in vec2 texCoordGlint;
#endif
layout(location = 7) in vec4 wallCoord; // nordtal, from item.vsh

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// BEGIN nordtal wall field (the previews compile this block as it stands)

// The marker: every texel of nordtal:item/border/wall_field is this colour, #4A63D9, one step
// beside the brand blue #4A63D8. No texture of Minecraft 26.3 or of the pack holds it.
const vec3 WALL_MARKER = vec3(74.0, 99.0, 217.0) / 255.0;

const vec3 WALL_DEEP = vec3(74.0, 99.0, 216.0) / 255.0;   // the brand blue: the veil
const vec3 WALL_ICE = vec3(160.0, 180.0, 255.0) / 255.0;  // the light in the rays
const vec3 WALL_SPARK = vec3(232.0, 238.0, 255.0) / 255.0; // lines and motes

const float WALL_TEXELS = 16.0;     // the field is drawn on the block's pixel grid
const float WALL_FADE_START = 24.0; // blocks from the camera where the wall starts to fade
const float WALL_FADE_END = 30.0;   // and where it is gone
const float WALL_NEAR_START = 2.5;  // closer than this the block grid shows in full
const float WALL_NEAR_END = 7.0;    // farther than this it does not show

bool wallIsMarker(vec4 texel) {
    return texel.a > 0.002 && all(lessThan(abs(texel.rgb - WALL_MARKER), vec3(0.0019)));
}

uint wallHash(uint x) {
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}

float wallRandom(int a, int b) {
    return float(wallHash(uint(a) * 0x9e3779b9u + wallHash(uint(b) + 0x51ed270bu)) >> 8u) / 16777216.0;
}

int wallWrap(float value, float period) {
    return int(value - period * floor(value / period));
}

// Value noise on a grid that repeats after `period` cells in x and in y, so a drift of one period
// per day of GameTime loops without a jump.
float wallNoise(vec2 at, vec2 period, int seed) {
    vec2 cell = floor(at);
    vec2 t = at - cell;
    t = t * t * (3.0 - 2.0 * t);
    int x0 = wallWrap(cell.x, period.x);
    int x1 = wallWrap(cell.x + 1.0, period.x);
    int y0 = wallWrap(cell.y, period.y) + seed * 4096;
    int y1 = wallWrap(cell.y + 1.0, period.y) + seed * 4096;
    return mix(mix(wallRandom(x0, y0), wallRandom(x1, y0), t.x), mix(wallRandom(x0, y1), wallRandom(x1, y1), t.x), t.y);
}

// coord: x = blocks along the wall, y = world height, zw = horizontal offset from the camera.
// envelope: 0 at the band's upper and lower end, 1 in its middle. gameTime: 0 to 1 in 1200 seconds.
vec4 wallField(vec4 coord, float envelope, float gameTime) {
    vec2 texel = floor(coord.xy * WALL_TEXELS);

    // Rays: tall soft columns that sway along the wall (1.75 pixels a second) and rise (2 to 2.3 pixels a second).
    float wide = wallNoise(vec2(texel.x / 7.0 + gameTime * 300.0, texel.y / 96.0 - gameTime * 25.0), vec2(300.0, 25.0), 1);
    float fine = wallNoise(vec2(texel.x / 3.0 - gameTime * 600.0, texel.y / 56.0 - gameTime * 50.0), vec2(600.0, 50.0), 2);
    float ray = smoothstep(0.38, 0.80, wide * 0.7 + fine * 0.3);
    ray = floor(ray * 4.0 + 0.5) / 4.0; // five flat steps, as pixel art shades

    float alpha = 0.22 + 0.32 * ray;
    vec3 colour = mix(WALL_DEEP, WALL_ICE, 0.30 * ray);

    // Strata: one pixel row at every block's lower edge. It shows the wall's run at a corner.
    bool onRow = mod(texel.y, WALL_TEXELS) < 0.5;
    if (onRow) {
        alpha += 0.09 + 0.12 * ray;
        colour = mix(colour, WALL_SPARK, 0.30);
    }

    // Close to the camera the block grid closes: the wall reads as solid before the push comes.
    float close = 1.0 - smoothstep(WALL_NEAR_START, WALL_NEAR_END, length(coord.zw));
    if (mod(texel.x, WALL_TEXELS) < 0.5) {
        alpha += 0.22 * close;
        colour = mix(colour, WALL_SPARK, 0.35 * close);
    }
    if (onRow) {
        alpha += 0.12 * close;
    }
    alpha *= 1.0 + 0.30 * close;

    // Motes: in about one pixel column of fourteen a bright pixel with a short tail rises, five blocks
    // apart, at 6 to 10 pixels a second.
    int column = wallWrap(texel.x, 4096.0);
    if (wallRandom(column, 11) < 0.07) {
        float loops = 90.0 + 30.0 * floor(wallRandom(column, 12) * 3.0);
        float head = floor(fract(wallRandom(column, 13) + gameTime * loops) * 80.0);
        float below = mod(head - texel.y, 80.0);
        if (below < 1.0) {
            alpha = 0.90;
            colour = WALL_SPARK;
        } else if (below < 5.0) {
            float tail = 1.0 - below / 5.0;
            alpha += 0.45 * tail;
            colour = mix(colour, WALL_SPARK, 0.6 * tail);
        }
    }

    float reach = 1.0 - smoothstep(WALL_FADE_START, WALL_FADE_END, length(coord.zw));
    return vec4(colour, min(alpha, 0.92) * envelope * reach);
}

// END nordtal wall field

// nordtal: the envelope is the texture's alpha, 1 to 255 for 0 to 1, read between two texel rows so
// the fade has no steps. A neighbour outside the wall's sprite counts as 0.
float wallEnvelope(ivec2 pixel, vec4 texel, float inTexel) {
    int towards = inTexel < 0.5 ? -1 : 1;
    ivec2 limit = textureSize(Sampler0, 0) - 1;
    vec4 next = texelFetch(Sampler0, clamp(pixel + ivec2(0, towards), ivec2(0), limit), 0);
    float here = (texel.a * 255.0 - 1.0) / 254.0;
    float there = wallIsMarker(next) ? (next.a * 255.0 - 1.0) / 254.0 : 0.0;
    return clamp(mix(here, there, abs(inTexel - 0.5)), 0.0, 1.0);
}

#ifndef OIT_ALPHA_ONLY
vec4 calculateFinalColor(vec4 color, bool wall) {
    if (!wall) { // nordtal: the wall glows by itself: no overlay, no lightmap, no glint
        color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
        color *= lightMapColor;

        #ifdef GLINT
        vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);// Glint color modulator?
        // Matches BlendFuntion.GLINT
        color.rgb += glintColor.rgb * glintColor.rgb;
        #endif
    }

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0);

    // nordtal: the exact texel under the fragment, whatever filter and mip level the sampler uses
    vec2 atlasPixel = texCoord0 * vec2(textureSize(Sampler0, 0));
    ivec2 pixel = ivec2(floor(atlasPixel));
    vec4 texel = texelFetch(Sampler0, pixel, 0);
    bool wall = wallIsMarker(texel);

    if (wall) {
        color = wallField(wallCoord, wallEnvelope(pixel, texel, fract(atlasPixel.y)), GameTime);
        color.a *= ColorModulator.a;
        if (color.a < 0.012) {
            discard;
        }
    } else {
        #ifdef ALPHA_CUTOUT
        if (color.a < ALPHA_CUTOUT) {
            discard;
        }
        #endif

        color *= vertexColor * ColorModulator;

        #ifdef GLINT
        color.a = max(color.a, GlintAlpha);
        #endif
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color, wall);
    #endif
}
