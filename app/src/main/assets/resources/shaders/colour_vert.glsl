#version 330 core

in vec2 pos;
in vec4 colour;

out vec4 varying_colour;

uniform mat3 posTransform;

void main() {
    vec2 transformedPos = (posTransform * vec3(pos, 1)).xy;
    gl_Position = vec4(transformedPos, 0, 1);
    varying_colour = colour;
}
