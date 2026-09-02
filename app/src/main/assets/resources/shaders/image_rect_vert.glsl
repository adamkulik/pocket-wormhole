#version 330 core

in vec2 pos;
in vec2 uv;
in vec4 filterColour;

out vec2 varying_uv;
out vec4 varying_colour;

uniform mat3 posTransform;
uniform mat3 uvTransform;

void main() {
    vec2 transformedPos = (posTransform * vec3(pos, 1)).xy;
    vec2 transformedUV = (uvTransform * vec3(uv, 1)).xy;
    gl_Position = vec4(transformedPos, 0, 1);
    varying_uv = transformedUV;
    varying_colour = filterColour;
}
