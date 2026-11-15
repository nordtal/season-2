#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla 26.3 core/item.fsh plus the Nordtal border wall (look A, the variant with snowflakes).
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
// beside the brand blue #4A63D8, and is not the colour drawn (WALL_DEEP is). No texture of Minecraft 26.3 or of the pack holds it.
const vec3 WALL_MARKER = vec3(74.0, 99.0, 217.0) / 255.0;

const vec3 WALL_DEEP = vec3(52.0, 90.0, 180.0) / 255.0;   // #345AB4, the logo's middle blue: the veil
const vec3 WALL_ICE = vec3(160.0, 180.0, 255.0) / 255.0;  // the light in the rays
const vec3 WALL_SPARK = vec3(232.0, 238.0, 255.0) / 255.0; // lines and snowflakes

const float WALL_FADE_START = 24.0; // blocks from the camera where the wall starts to fade
const float WALL_FADE_END = 30.0;   // and where it is gone
const float WALL_NEAR_START = 1.5;  // closer than this the block grid shows in full
const float WALL_NEAR_END = 6.0;    // farther than this it does not show
const float WALL_SNOW = 0.55;       // the alpha a flake adds at its centre
const float WALL_SNOW_SHARE = 0.6;  // the share of cells that hold a flake

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

// Smooth value noise (quintic interpolation, so its slope is continuous too) on a grid that repeats
// after `period` cells in x and in y, so a drift of one period per day of GameTime loops without a jump.
float wallNoise(vec2 at, vec2 period, int seed) {
    vec2 cell = floor(at);
    vec2 t = at - cell;
    t = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    int x0 = wallWrap(cell.x, period.x);
    int x1 = wallWrap(cell.x + 1.0, period.x);
    int y0 = wallWrap(cell.y, period.y) + seed * 4096;
    int y1 = wallWrap(cell.y + 1.0, period.y) + seed * 4096;
    return mix(mix(wallRandom(x0, y0), wallRandom(x1, y0), t.x), mix(wallRandom(x0, y1), wallRandom(x1, y1), t.x), t.y);
}

// A soft line of the given half width (in blocks) at every whole block of `at`, 1 on the line.
float wallLine(float at, float halfWidth) {
    float d = abs(fract(at + 0.5) - 0.5) / halfWidth;
    return exp(-d * d);
}

// One layer of snowflakes. The wall is cut into lanes of `width` blocks, each lane into cells of
// `spacing` blocks that move down `loops` cells per day of GameTime, so the layer loops without a
// jump. A cell holds at most one flake: a soft round point of `radius` blocks, at a place of its
// own in the cell, that sways sideways once in 5, 6 or 7.5 seconds. The flake never reaches the
// cell's edge, so no neighbour has to be looked at and nothing is cut.
float wallFlakes(vec2 at, float gameTime, float width, float spacing, float loops, float radius, int seed) {
    float lane = floor(at.x / width);
    int column = wallWrap(lane, 4096.0) + seed * 8192;
    float fall = at.y / spacing + gameTime * loops + wallRandom(column, 1);
    float cell = floor(fall);
    int flake = wallWrap(cell, loops) + 16;
    if (wallRandom(column, flake) > WALL_SNOW_SHARE) {
        return 0.0;
    }
    float turns = 160.0 + 40.0 * floor(wallRandom(column, flake + 1024) * 3.0);
    float sway = sin(6.2831853 * (gameTime * turns + wallRandom(column, flake + 2048)));
    float centre = 0.5 + 0.30 * (wallRandom(column, flake + 3072) - 0.5) + 0.14 * sway;      // in lanes
    float height = 0.5 + (1.0 - 6.0 * radius / spacing) * (wallRandom(column, flake + 4096) - 0.5); // in cells
    float size = radius * (0.75 + 0.5 * wallRandom(column, flake + 5120));
    vec2 d = vec2((at.x / width - lane - centre) * width, (fall - cell - height) * spacing) / size;
    return exp(-dot(d, d));
}

// coord: x = blocks along the wall, y = world height, zw = horizontal offset from the camera.
// envelope: 0 at the band's upper and lower end, 1 in its middle. gameTime: 0 to 1 in 1200 seconds.
// Everything is continuous in space and time: no pixel grid, no steps.
vec4 wallField(vec4 coord, float envelope, float gameTime) {
    vec2 at = coord.xy;

    // Rays: tall soft folds that sway along the wall (0.11 and 0.09 blocks a second, against each
    // other) and rise (0.13 to 0.15 blocks a second). Three smooth layers, the widest the strongest.
    float broad = wallNoise(vec2(at.x / 1.6 + gameTime * 80.0, at.y / 9.0 - gameTime * 18.0), vec2(80.0, 18.0), 1);
    float wide = wallNoise(vec2(at.x / 0.55 - gameTime * 200.0, at.y / 6.0 - gameTime * 30.0), vec2(200.0, 30.0), 2);
    float fine = wallNoise(vec2(at.x / 0.22 + gameTime * 300.0, at.y / 4.0 - gameTime * 40.0), vec2(300.0, 40.0), 3);
    float ray = smoothstep(0.30, 0.82, broad * 0.5 + wide * 0.35 + fine * 0.15);

    float alpha = 0.21 + 0.31 * ray;
    vec3 colour = mix(WALL_DEEP, WALL_ICE, 0.32 * ray);

    // Close to the camera a soft block grid comes up: the wall reads as solid before the push comes.
    // Farther away only a breath of the horizontal lines stays, which shows the wall's run at a corner.
    float close = 1.0 - smoothstep(WALL_NEAR_START, WALL_NEAR_END, length(coord.zw));
    float rows = wallLine(at.y, 0.045);
    float columns = wallLine(at.x, 0.045);
    float grid = rows * (0.045 + 0.13 * close) + columns * 0.15 * close * (1.0 - 0.5 * rows);
    alpha += grid;
    colour = mix(colour, WALL_SPARK, min(1.0, grid * 2.2));
    alpha *= 1.0 + 0.25 * close;

    // Snow: small soft flakes drift down the wall and sway sideways, in three layers, the largest
    // the fastest: 0.45, 0.32 and 0.22 blocks a second. They fade out at the band's lower end
    // through the envelope, like everything else.
    float snow = wallFlakes(at, gameTime, 1.00, 3.0, 180.0, 0.060, 21)
               + wallFlakes(at, gameTime, 0.70, 2.4, 160.0, 0.045, 22) * 0.80
               + wallFlakes(at, gameTime, 0.45, 2.0, 132.0, 0.032, 23) * 0.55;
    snow = min(1.0, snow);
    alpha += WALL_SNOW * snow;
    colour = mix(colour, mix(WALL_ICE, WALL_SPARK, snow), min(1.0, snow * 1.4));

    float reach = 1.0 - smoothstep(WALL_FADE_START, WALL_FADE_END, length(coord.zw));
    alpha = min(alpha, 0.92) * envelope * reach;

    // Half a step of 8-bit colour of screen noise, so the long fades do not band on the screen.
    float grain = float(wallHash(uint(gl_FragCoord.x) + wallHash(uint(gl_FragCoord.y))) >> 8u) / 16777216.0 - 0.5;
    return vec4(colour, max(0.0, alpha + grain * envelope * reach / 255.0));
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
