#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla 26.3 core/item.vsh plus three outputs for the Nordtal border wall. Every Nordtal line is
// marked "nordtal"; the rest is vanilla, unchanged.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:globals.glsl> // nordtal: CameraBlockPos, CameraOffset

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
#endif

layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif

// nordtal: x = blocks along the wall, y = world height in blocks, zw = horizontal offset from the camera.
layout(location = 7) out vec4 wallCoord;
// nordtal: the vertex seen from the camera, in blocks: x along the wall, y up, z at a right angle to the wall.
layout(location = 8) out vec3 wallView;
// nordtal: the ground under the panel, in blocks, or below -100000 when the server does not know it.
layout(location = 9) flat out float wallGround;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    #endif
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    #ifndef OIT_ALPHA_ONLY
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    #endif

    texCoord0 = UV0;
    #ifdef GLINT
    #ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    #endif
    #endif

    // nordtal: Position is relative to the camera on the world's axes (the fog functions rely on that
    // too), and the camera is at CameraBlockPos - CameraOffset, as core/terrain.vsh shows. The wall's
    // direction is the horizontal tangent of the face, with one sign for both faces of a panel, so
    // both sides and all panels of one edge share one pattern that stays where it is in the world.
    vec2 tangent = vec2(-Normal.z, Normal.x);
    if (tangent.x < -0.0001 || (abs(tangent.x) <= 0.0001 && tangent.y < 0.0)) {
        tangent = -tangent;
    }
    vec3 world = (Position - CameraOffset) + vec3(CameraBlockPos);
    wallCoord = vec4(dot(world.xz, tangent), world.y, Position.xz);
    // nordtal: all three are linear in Position, so they are exact between the vertices
    wallView = vec3(dot(Position.xz, tangent), Position.y, dot(Position.xz, vec2(tangent.y, -tangent.x)));
    // nordtal: the server sends the ground plus 2048 in the item's tint, 5 bits a channel, each stored
    // as 130 + 4 times its bits, so a channel read one off still decodes. White means not known.
    ivec3 channel = ivec3(round(Color.rgb * 255.0));
    ivec3 bits = (channel - 128) >> 2;
    wallGround = all(equal(channel, ivec3(255))) ? -1000000.0 : float((bits.r << 10 | bits.g << 5 | bits.b) - 2048);
}
