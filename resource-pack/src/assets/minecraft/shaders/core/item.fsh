#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla 26.3 core/item.fsh plus the Nordtal border wall.
// A fragment whose texel carries the marker colour is drawn as the wall's field; every other
// fragment takes the vanilla path, unchanged. Every Nordtal line is marked "nordtal".

#include <minecraft:globals.glsl> // nordtal: always, for GameTime and ScreenSize (vanilla includes it for GLINT only)
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
layout(location = 8) in vec3 wallView;  // nordtal, from item.vsh
layout(location = 9) flat in float wallGround; // nordtal, from item.vsh

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// BEGIN nordtal wall field

// The marker: every texel of nordtal:item/border/field is this colour, #4A63D9, one step beside the
// brand blue #4A63D8, and is not a colour drawn. No texture of Minecraft 26.3 or of the pack holds it.
// Its alpha stays below 1, so the client draws the panel as translucent and blends the field.
const vec3 WALL_MARKER = vec3(74.0, 99.0, 217.0) / 255.0;

const float WALL_EYE = 1.62;        // blocks from the feet up to the camera, for a player standing
const float WALL_ABOVE = 7.0;       // blocks: the band's upper end above the feet
const float WALL_BELOW = 3.0;       // and its lower end below the ground, or below the feet when they are lower
const float WALL_ROOM = 2.0;        // blocks at the band's upper end where only the flakes are, above the wall
const float WALL_TOP = 5.0;         // blocks over which the wall fades in below its upper end
const float WALL_BOTTOM = 1.75;     // blocks over which it fades out above the band's lower end
const float WALL_SNOW_IN = 1.0;     // blocks below the band's upper end where a flake has come in whole: half the room
const float WALL_SNOW_OUT = 0.25;   // blocks above the band's lower end where a flake starts to go
const float WALL_FADE_START = 0.8;  // of the distance where the wall has faded out, where it starts to fade
const float WALL_FADE_END = 30.0;   // blocks from the camera where the wall has faded out along its length, at the camera's height
const float WALL_SLOPE = 0.75;      // blocks farther it reaches for each block it lies below the camera
const float WALL_LEVEL = 6.0;       // blocks below the camera where it starts to reach farther
const float WALL_DEPTH = 96.0;      // blocks below that where it reaches no farther
const float WALL_NEAR_START = 1.5;  // closer than this the wall shows its full strength
const float WALL_NEAR_END = 6.0;    // farther than this it shows its normal one

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

uint wallHash2(int a, int b) {
    return wallHash(uint(a) * 0x9e3779b9u + wallHash(uint(b) + 0x51ed270bu));
}

float wallUnit(uint h) {
    return float(h >> 8u) / 16777216.0;
}

float wallRandom(int a, int b) {
    return wallUnit(wallHash2(a, b));
}

int wallWrap(float value, float period) {
    return int(value - period * floor(value / period));
}

// Smooth value noise (quintic interpolation) on a grid that repeats after `period` cells in x and
// in y, so a drift of whole periods per day of GameTime loops without a jump.
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

// 0 at a, 1 at b, with no slope and no bend at either end: the softest way in and out.
float wallEase(float a, float b, float x) {
    float t = clamp((x - a) / (b - a), 0.0, 1.0);
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

// The wall's depth below its upper end in blocks, 0 to WALL_TOP, out of the fragment's place in the
// band: fromTop blocks below its upper end and fromBottom above its lower end. The wall's upper end
// lies WALL_ROOM below the band's, so the room above it holds flakes only. At the band's lower end
// the same value runs from 0 at the end to WALL_TOP WALL_BOTTOM blocks above it, so every fade laid
// over the depth also closes the band below, only faster.
float wallDepth(float fromTop, float fromBottom) {
    return min(clamp(fromTop - WALL_ROOM, 0.0, WALL_TOP), clamp(fromBottom * (WALL_TOP / WALL_BOTTOM), 0.0, WALL_TOP));
}

// How much of a flake shows at this depth below the band's upper end: none at the band's upper end,
// all of it WALL_SNOW_IN blocks below, half way down the room above the wall, and all of it from
// there until the last WALL_SNOW_OUT blocks of the band. So the snow fades in from the top and no
// flake fades on its way down.
float wallSnowShows(float fromTop, float fromBottom) {
    return wallEase(0.0, WALL_SNOW_IN, fromTop) * wallEase(0.0, WALL_SNOW_OUT, fromBottom);
}

// How many screen pixels one texel (a sixteenth of a block) covers, x: along the wall, y: upwards.
// view is the fragment's place seen from the camera: x along the wall, y up, z at a right angle to
// it. Looking down the wall's length, or down its face from above, a texel shrinks below a pixel;
// texel-sized detail is faded out there instead of flickering. It assumes the default field of
// view; no derivatives, because the client check runs on software rendering.
vec2 wallTexelSize(vec3 view) {
    float focal = 0.7 * max(ScreenSize.y, 720.0);
    return focal / (16.0 * max(dot(view, view), 0.0001)) * vec2(length(view.yz), length(view.xz));
}

// The snowflake sprites, pixel art, all small: nothing is larger than these, at no distance. Each
// is 1 or 3 texels square and the same when turned upside down, so only its upper row and the
// middle row are stored: 8 bits a row, the top row lowest.
//   0 a dot (1 texel)    1 a flake (4 texels)    2 the same with its middle closed (5 texels)
//      #                    .#.                     .#.
//                           #.#                     ###
//                           .#.                     .#.
const uint WALL_SPRITE[3] = uint[3](0x01u, 0x0502u, 0x0702u);
const int WALL_SPRITE_SIZE[3] = int[3](1, 3, 3);

float wallSprite(int kind, ivec2 p) {
    int size = WALL_SPRITE_SIZE[kind];
    if (p.x < 0 || p.y < 0 || p.x >= size || p.y >= size) {
        return 0.0;
    }
    return float((WALL_SPRITE[kind] >> uint(min(p.y, size - 1 - p.y) * 8 + p.x)) & 1u);
}

// Roughly a standard normal value out of one hash: the sum of its four bytes.
float wallNormal(uint h) {
    return (float(h & 255u) + float((h >> 8u) & 255u) + float((h >> 16u) & 255u) + float(h >> 24u) - 510.0) / 147.8;
}

// The flakes of one lane of a layer, see wallFlakes(). across and height are the fragment's place
// in texels; the flake may reach up to two texels into the next lane. 1 on a texel of a flake.
float wallLane(float across, float height, float laneAt, float gameTime, float lane, float cell, float share, int kind, float pace, int seed) {
    int column = wallWrap(laneAt, 4096.0) + seed * 8192;
    uint c = wallHash2(column, 1);
    uint c2 = wallHash(c ^ 0x5bd1e995u);
    uint c3 = wallHash(c2 ^ 0x1b873593u);
    float loops = max(1.0, floor((3000.0 + 1536.0 * clamp(wallNormal(c), -1.5, 1.5)) * pace / cell + 0.5));
    float lanes = floor(3840.0 * wallNormal(c2) * pace / lane + 0.5);
    float fallen = gameTime * loops * cell + float(c3 & 65535u) / 65536.0 * cell;
    float drifted = gameTime * lanes * lane + float(c3 >> 16u) / 65536.0 * lane;
    float y = height + fallen;
    float cellAt = floor(y / cell);
    uint h = wallHash2(column, wallWrap(cellAt, loops) + 16);
    if (float(h & 255u) >= share * 256.0) {
        return 0.0;
    }
    int pick = kind;
    if (kind == 1 && ((h >> 8u) & 7u) % 3u == 0u) {
        pick = 1 + (int(floor(gameTime * 1800.0 + float((h >> 11u) & 7u) / 8.0)) & 1);
    }
    int size = WALL_SPRITE_SIZE[pick];
    // its place in the lane, drifting around it as vanilla's flakes drift around their column
    float left = float((h >> 14u) & 255u) / 256.0 * lane + drifted;
    left -= lane * floor(left / lane);
    float low = 1.0 + floor(float((h >> 22u) & 255u) / 256.0 * (cell - float(size) - 1.0));
    return wallSprite(pick, ivec2(floor(vec2(across - laneAt * lane - left, y - cellAt * cell - low))));
}

// One layer of falling flakes, in texels (sixteenths of a block), moving as vanilla's snowfall
// does. Vanilla draws its snow one block column at a time, and every column has a constant fall
// and a constant drift sideways of its own: 0.156 + 0.08 g blocks a second down and 0.2 g blocks
// a second sideways, g a standard normal value, from a random start. A flake that drifts out of
// one side of its column comes back in on the other. Here the wall is cut into lanes of `lane`
// texels that are those columns, with the same numbers times `pace`: a layer with a higher pace
// is a column nearer to the eye. A lane is cut into cells of `cell` texels, and `share` of them
// hold one flake, at a place of its own. `kind` 0 is a layer of single texels. `kind` 1 is a layer
// of flakes of 4 texels, of which one in three closes and opens its middle (4 texels, then 5) 1.5
// times a second. Fall and drift are whole cells and whole lanes per day of GameTime, so nothing
// jumps at the wrap. The flakes move smoothly, as vanilla's do, and keep their texels' size.
// Returns 1 on a texel of a flake. A flake is flat: one colour, no shadow.
float wallFlakes(vec2 texel, float gameTime, float lane, float cell, float share, int kind, float pace, int seed) {
    float across = texel.x + float((seed * 7) & 15);   // every layer's lanes begin elsewhere
    float laneAt = floor(across / lane);
    float flakes = wallLane(across, texel.y, laneAt, gameTime, lane, cell, share, kind, pace, seed);
    if (kind == 1 && across - laneAt * lane < 3.0) {   // a flake of the lane before may reach in, whole
        flakes = max(flakes, wallLane(across, texel.y, laneAt - 1.0, gameTime, lane, cell, share, kind, pace, seed));
    }
    return flakes;
}

// All the snow on the wall, small, falling as vanilla's snowfall does: a near layer of 4-texel
// flakes, then two of single texels, each slower and the last fainter, as columns farther away
// are. Lane, cell, share of cells with a flake, kind, pace against vanilla's own speed. Returns
// how strongly the texel is snow: 1 a near or middle flake, 0.5 a far one, 0 none.
float wallSnow(vec2 texel, float gameTime) {
    float near = wallFlakes(texel, gameTime, 16.0, 18.0, 0.56, 1, 1.4, 31);
    float mid = wallFlakes(texel, gameTime, 16.0, 8.0, 0.65, 0, 1.0, 32);
    float far = wallFlakes(texel, gameTime, 16.0, 7.0, 0.70, 0, 0.65, 33);
    return max(max(near, mid), 0.5 * far);
}

// The wall's look: a body of pixel-art ice below, rays of light above, everything on the world's
// texel grid, 16 texels a block; a texel is flat in colour.

// The five shades of the body, darkest first, each with its alpha.
const vec4 WALL_SHADE[5] = vec4[5](
    vec4(vec3(22.0, 38.0, 108.0) / 255.0, 0.70),   // #16266C  a block's outline, close up
    vec4(vec3(34.0, 62.0, 146.0) / 255.0, 0.62),   // #223E92  also the faintest ray
    vec4(vec3(52.0, 90.0, 180.0) / 255.0, 0.57),   // #345AB4  the logo's middle blue, the most common
    vec4(vec3(74.0, 118.0, 214.0) / 255.0, 0.57),  // #4A76D6  also the strongest ray
    vec4(vec3(124.0, 160.0, 244.0) / 255.0, 0.64)  // #7CA0F4  a block's upper row, a bright column
);
const vec3 WALL_LIGHT = vec3(196.0, 212.0, 255.0) / 255.0;  // #C4D4FF  light that climbs a column
const vec3 WALL_SPARK = vec3(222.0, 232.0, 255.0) / 255.0;  // #DEE8FF  light on a bright column
const vec3 WALL_FLAKE = vec3(240.0, 245.0, 255.0) / 255.0;  // #F0F5FF  a flake, every texel of it

const float WALL_MOTTLE = 1.45;      // how far the blobs spread over the three middle shades
const float WALL_GRAIN = 0.16;       // how much a single texel may differ from its blob
const float WALL_FOLD_LIFT = 0.36;   // how much a strong fold of the curtain lifts the body's shade below it
const float WALL_RAY_LOW = 0.58;     // the alpha of the faintest ray
const float WALL_RAY_HIGH = 0.80;    // and of the strongest
const float WALL_TOP_HIGH = 0.05;    // blocks below the wall's upper end where the tallest ray begins
const float WALL_TOP_LOW = 2.4;      // and where the shortest one begins
const float WALL_TAIL = 1.0;         // blocks over which a ray comes in below its beginning, at least; up to twice that
const float WALL_BODY_HIGH = 1.6;    // blocks below the upper end where the first column turns from ray into body
const float WALL_BODY_LOW = 2.5;     // and where the last one starts to
const float WALL_BODY_LENGTH = 0.8;  // blocks a column takes for that turn
const float WALL_GAPS = 0.12;        // the share of columns that are a gap among the rays
const float WALL_BRIGHT = 0.06;      // the share of columns that are a bright column
const float WALL_PULSES = 0.11;      // the share of a column's cells that carry a light
const float WALL_PULSE_BODY = 0.8;   // how strong a light is in the body; among the rays it is 1
const float WALL_OUTLINE_CLEAR = 3.0; // blocks from the camera within which a block's outline is clear
const float WALL_OUTLINE_FAINT = 7.0; // and beyond which it is faint
const float WALL_OUTLINE_FAR = 0.22;  // how much of the outline is left there
const float WALL_FLAKE_ALPHA = 0.96;

// coord: x = blocks along the wall, y = world height, zw = horizontal offset from the camera.
// view: the same place seen from the camera (along the wall, up, at a right angle to it).
// fromTop, fromBottom: blocks below the band's upper end and above its lower end. fadeEnd: blocks
// from the camera where the wall has faded out at this height. gameTime: 0 to 1 in 1200 seconds.
vec4 wallField(vec4 coord, vec3 view, float fromTop, float fromBottom, float fadeEnd, float gameTime) {
    vec2 texel = coord.xy * 16.0;
    vec2 q = floor(texel);                      // the texel, fixed in the world
    vec2 inBlock = q - 16.0 * floor(q / 16.0);  // 0 to 15 in its block
    int column = wallWrap(q.x, 65536.0);
    uint grain = wallHash2(column, wallWrap(q.y, 65536.0));
    // Far away, and looking down the wall's length, a texel is smaller than a pixel: there the
    // texel-sized parts (grain, outlines, single columns, single lights) melt into their average.
    vec2 size = wallTexelSize(view);
    float detail = smoothstep(0.6, 1.6, min(size.x, size.y));
    float columns = smoothstep(0.6, 1.6, size.x);
    float close = 1.0 - smoothstep(WALL_NEAR_START, WALL_NEAR_END, length(coord.zw));
    // at the texel's middle, so every fade is flat per texel
    float middle = (texel.y - q.y - 0.5) / 16.0 * smoothstep(0.6, 1.6, size.y);
    float depth = wallDepth(fromTop + middle, fromBottom - middle);

    uint h = wallHash2(column, 91);              // the column's own numbers
    float own = wallUnit(h);
    float own2 = float(h & 255u) / 255.0;
    uint h2 = wallHash(h ^ 0x2c1b3c6du);
    float kind = wallUnit(h2);
    float own3 = float(h2 & 255u) / 255.0;
    float gap = (kind < WALL_GAPS ? 1.0 : 0.0) * columns;
    float bright = (kind > 1.0 - WALL_BRIGHT ? 1.0 : 0.0) * columns;

    // The curtain: northern-lights waves travelling along the wall at 1.25 to 1.60 blocks a second,
    // periodic over GameTime so nothing jumps on wrap. Columns grow, shrink and shimmer with each wave.
    float x = mix(coord.x, (q.x + 0.5) / 16.0, columns);
    float fold = 0.52 * wallNoise(vec2(x / 3.2 - gameTime * 600.0, gameTime * 300.0), vec2(600.0, 300.0), 1)
               + 0.33 * wallNoise(vec2(x / 1.25 + gameTime * 1200.0, gameTime * 420.0 + 17.5), vec2(1200.0, 420.0), 2)
               + 0.15 * wallNoise(vec2(x / 0.5 - gameTime * 3600.0, gameTime * 600.0 + 41.0), vec2(3600.0, 600.0), 5);
    fold = smoothstep(0.24, 0.76, fold);

    // The upper part, the rays: one texel column is one ray, in five flat steps from the faintest
    // to the strongest, with gaps and a few bright ones. A ray begins at a height of its own, high
    // where the wave is strong, and comes in softly below it, so the wall has no hard upper edge.
    float level = clamp(0.10 + 0.90 * fold + (own - 0.5) * 0.42 * columns, 0.0, 1.0);
    level = mix(level, floor(level * 4.999) / 4.0, columns);
    float top = mix(WALL_TOP_LOW, WALL_TOP_HIGH, clamp(fold * 0.92 + (own2 - 0.5) * 0.34 * columns + 0.35 * bright, 0.0, 1.0));
    float rise = wallEase(top, top + WALL_TAIL * (1.0 + own * columns), depth) * wallEase(0.0, 1.2, depth);
    float shimmer = smoothstep(0.55, 0.95, fold);
    vec4 ray = vec4(
        mix(mix(WALL_SHADE[1].rgb, WALL_SHADE[3].rgb, level), WALL_SHADE[4].rgb, 0.45 * shimmer),
        mix(WALL_RAY_LOW, WALL_RAY_HIGH, level) * (0.78 + 0.22 * smoothstep(1.0, 3.0, depth)) * (0.85 + 0.25 * shimmer));
    ray = mix(ray, vec4(WALL_SHADE[4].rgb, 0.86), 0.8 * bright);
    ray.a *= 1.0 - gap * (0.62 - 0.32 * close);

    // The lower part, the body: mottling as on ice. Two fields of blobs, 6 and 10 texels wide,
    // read at the texel's middle, climb 3 and 2 texels a second (the second also slides 1 along
    // the wall), so the blobs flow upwards and change shape. It picks one of the three middle
    // shades; a strong fold above lifts it.
    float blobs = 0.55 * wallNoise(vec2(q.x + 0.5, q.y + 0.5 - gameTime * 3600.0) / 6.0, vec2(4096.0, 300.0), 3)
                + 0.45 * wallNoise(vec2(q.x + 0.5 + gameTime * 1200.0, q.y + 0.5 - gameTime * 2400.0) / 10.0, vec2(60.0, 120.0), 4);
    float tone = 0.5 + (blobs - 0.5) * WALL_MOTTLE + (fold - 0.5) * WALL_FOLD_LIFT;
    int plain = 1 + int(clamp(floor(tone * 3.0), 0.0, 2.0));
    int shade = 1 + int(clamp(floor((tone + (wallUnit(grain) - 0.5) * WALL_GRAIN) * 3.0), 0.0, 2.0));
    vec4 body = mix(WALL_SHADE[plain], WALL_SHADE[shade], detail);
    // Every block has an outline: its bottom row and one side the darkest shade, its top row the
    // lightest. Far off it is only a hint; it comes up as the camera nears and is clear within
    // WALL_OUTLINE_CLEAR blocks, so a player walking up sees the wall turn into blocks.
    float outline = mix(WALL_OUTLINE_FAR, 1.0, 1.0 - smoothstep(WALL_OUTLINE_CLEAR, WALL_OUTLINE_FAINT, length(view))) * detail;
    if (inBlock.y < 0.5 || inBlock.x < 0.5) {
        body = mix(body, WALL_SHADE[0], outline);
    } else if (inBlock.y > 14.5) {
        body = mix(body, WALL_SHADE[4], 0.7 * outline);
    }
    body = mix(body, WALL_SHADE[4], 0.35 * bright);

    // Where the two meet: every column turns from ray into body at a depth of its own and takes
    // WALL_BODY_LENGTH blocks for it, so there is no line between them.
    float turn = mix(WALL_BODY_HIGH, WALL_BODY_LOW, mix(0.5, own3, columns));
    float solid = wallEase(turn, turn + WALL_BODY_LENGTH, depth);
    vec3 colour = mix(ray.rgb, body.rgb, solid);
    float alpha = mix(ray.a, body.a, solid);

    // Light climbs a few columns, from the ground into the ray's tip: a bright head with a tail of
    // 5 to 12 texels below it, 9.6, 12 or 14.4 texels a second, a whole texel at a time.
    float climb = 480.0 + 120.0 * floor(own2 * 2.999);   // cells of 24 texels a day
    float y = q.y - floor(gameTime * climb * 24.0) + floor(own * 24.0);
    float cellAt = floor(y / 24.0);
    uint p = wallHash2(column + 131072, wallWrap(cellAt, climb));
    float pulse = 0.0;
    if (float(p & 255u) < WALL_PULSES * 256.0) {
        float below = 12.0 + float((p >> 8u) % 12u) - (y - cellAt * 24.0);
        float tail = 5.0 + float((p >> 12u) & 7u);
        pulse = below >= 0.0 && below < tail ? 1.0 - below / tail : 0.0;
    }
    pulse = mix(0.02, pulse * pulse * (0.4 + 0.6 * pulse), columns);
    pulse *= mix(1.0, WALL_PULSE_BODY, solid);
    colour = mix(colour, mix(WALL_LIGHT, WALL_SPARK, bright), pulse);
    alpha = (alpha + 0.2 * pulse) * rise;

    // The snow: flat flakes of one colour, from the top of the room above the wall down to the
    // band's lower end. Far away they are fainter, not smaller.
    float snow = wallSnow(texel, gameTime) * wallSnowShows(fromTop + middle, fromBottom - middle) * (0.45 + 0.55 * detail);
    colour = mix(colour, WALL_FLAKE, min(1.0, 2.0 * snow));
    alpha = mix(alpha, WALL_FLAKE_ALPHA, snow);

    float reach = 1.0 - smoothstep(WALL_FADE_START * fadeEnd, fadeEnd, length(coord.zw));
    return vec4(colour, min(alpha * (1.0 + 0.2 * close), 0.97) * reach);
}

const float WALL_DOME_ALT_START = 1.5;   // altitude above ground where the flight dome starts to rise
const float WALL_DOME_ALT_END = 4.5;     // altitude above ground where the dome reaches full apex headroom
const float WALL_DOME_HALF_LOW = 10.0;   // dome half-width at low to mid altitude (20 blocks full width)
const float WALL_DOME_HALF_HIGH = 30.0;  // capped dome half-width at high altitude (60 blocks full width)
const float WALL_DOME_PROX_FULL = 10.0;  // horizontal distance where the flight dome reaches full opacity
const float WALL_DOME_PROX_END = 30.0;   // horizontal distance where the flight dome fades out

// Smooth maximum of a and b with fillet width k.
float wallSmoothMax(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / k;
    return max(a, b) + h * h * k * 0.25;
}

// Unified grounded border contour: a steady terrain-anchored band smoothly merged into an organic
// flight dome centred on the player, anchored down to under - WALL_BELOW with no mid-air gap.
// Underground (feet < under - 2.0), a local band stays at player height without stretching through rock.
// Returns the field's colour, or a negative alpha where nothing is drawn.
vec4 wallBand(vec4 coord, vec3 view, float ground, float gameTime) {
    float camera = coord.y - view.y;
    float feet = camera - WALL_EYE;
    float under = ground > -100000.0 ? ground : feet;

    float groundTop = under + WALL_ABOVE;
    float groundBottom = under - WALL_BELOW;
    float tFadeEnd = WALL_FADE_END + WALL_SLOPE * clamp(camera - coord.y - WALL_LEVEL, 0.0, WALL_DEPTH);

    float alt = max(0.0, feet - under);
    float flight = wallEase(WALL_DOME_ALT_START, WALL_DOME_ALT_END, alt);
    float domeProx = 1.0 - wallEase(WALL_DOME_PROX_FULL, WALL_DOME_PROX_END, abs(view.z));

    float effectiveTop = groundTop;
    float perimScale = 1.0;
    float proxMask = 1.0;
    float fadeEnd = tFadeEnd;

    if (flight > 0.001 && domeProx > 0.001) {
        float apexTop = mix(groundTop, feet + WALL_ABOVE, flight);
        float domeRise = max(0.0, apexTop - groundTop);
        float domeHalfWidth = mix(WALL_DOME_HALF_LOW, WALL_DOME_HALF_HIGH, smoothstep(10.0, 110.0, alt));

        // Slow multi-frequency periodic waves deform the dome organically so it never reads as an ellipse.
        float cWave1 = wallNoise(vec2(coord.x / 11.0 + gameTime * 24.0, gameTime * 36.0), vec2(24.0, 36.0), 11);
        float cWave2 = wallNoise(vec2(coord.x / 4.5 - gameTime * 48.0, gameTime * 60.0 + 7.3), vec2(48.0, 60.0), 12);
        float cWave3 = wallNoise(vec2(coord.x / 2.2 + gameTime * 72.0, gameTime * 90.0 + 19.1), vec2(72.0, 90.0), 13);
        float contourNoise = (cWave1 - 0.5) * 0.55 + (cWave2 - 0.5) * 0.30 + (cWave3 - 0.5) * 0.15;

        float organicHalfWidth = domeHalfWidth * (1.0 + 0.30 * contourNoise);
        float u = abs(view.x) / organicHalfWidth;
        float q2 = max(0.0, 1.0 - u * u);
        float bell = pow(q2, 0.42);
        float verticalWave = contourNoise * min(domeRise * 0.25, 5.5) * smoothstep(0.0, 0.25, bell);

        float domeTop = (groundTop - 2.5) + (domeRise + 2.5) * bell + verticalWave;
        float smoothK = 2.5 * flight;
        float fullTop = wallSmoothMax(groundTop, domeTop, smoothK);
        effectiveTop = mix(groundTop, fullTop, smoothstep(0.0, 0.25, domeProx));

        float dBellDu = 0.84 * min(u, 1.0) * pow(max(q2, 0.02), -0.58) * (1.0 - smoothstep(0.96, 1.02, u));
        float slope = (domeRise + 2.5) * dBellDu / organicHalfWidth;
        perimScale = mix(1.0, inversesqrt(1.0 + slope * slope * 0.65), smoothstep(groundTop, groundTop + 3.0, coord.y));

        float aboveGround = wallEase(groundTop, groundTop + 3.5, coord.y);
        proxMask = mix(1.0, domeProx, aboveGround);
        float domeReach = length(vec2(WALL_DOME_PROX_END, organicHalfWidth)) + 4.0;
        fadeEnd = max(tFadeEnd, mix(tFadeEnd, domeReach, aboveGround));
    }

    vec4 field = vec4(0.0);
    float fromTop = (effectiveTop - coord.y) * perimScale;
    float fromBottom = coord.y - groundBottom;
    if (fromTop >= 0.0 && fromBottom >= 0.0 && length(coord.zw) <= fadeEnd) {
        field = wallField(coord, view, fromTop, fromBottom, fadeEnd, gameTime);
        field.a *= proxMask;
    }

    vec4 cave = vec4(0.0);
    if (feet < under - 2.0) {
        float caveMask = wallEase(2.0, 5.0, under - feet) * (1.0 - wallEase(8.0, 18.0, abs(view.x)));
        float uFromTop = feet + WALL_ABOVE - coord.y;
        float uFromBottom = coord.y - (feet - WALL_BELOW);
        if (caveMask > 0.005 && uFromTop >= 0.0 && uFromBottom >= 0.0 && length(coord.zw) <= 25.0) {
            cave = wallField(coord, view, uFromTop, uFromBottom, 25.0, gameTime);
            cave.a *= caveMask;
        }
    }

    float alpha = max(field.a, cave.a);
    if (alpha < 0.012) {
        return vec4(-1.0);
    }
    vec3 rgb = (field.rgb * field.a + cave.rgb * cave.a) / max(field.a + cave.a, 0.0001);
    return vec4(rgb, alpha);
}

// END nordtal wall field

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
        color = wallBand(wallCoord, wallView, wallGround, GameTime);
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
