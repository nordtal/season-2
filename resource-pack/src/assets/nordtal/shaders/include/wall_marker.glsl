// The border wall's marker, shared by core/item.vsh and core/item.fsh.
// Every texel of nordtal:item/border/field is this colour, #4A63D9, one step beside the brand blue
// #4A63D8, and is not a colour drawn. No texture of Minecraft 26.3 or of the pack holds it. Its alpha
// stays below 1, so the client draws the panel as translucent and blends the field. The texture's
// upper half has alpha 2 of 255 and its lower half 1, so a vertex knows which end of the panel it is.
const vec3 WALL_MARKER = vec3(74.0, 99.0, 217.0) / 255.0;
const float WALL_UPPER = 1.5 / 255.0;

bool wallIsMarker(vec4 texel) {
    return texel.a > 0.002 && all(lessThan(abs(texel.rgb - WALL_MARKER), vec3(0.0019)));
}
