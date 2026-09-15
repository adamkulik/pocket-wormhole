#version 330 core

in vec2 varying_uv;
in vec4 varying_colour;

out vec4 colour;

uniform sampler2D tex;

void main() {
    vec4 texColour = texture(tex, varying_uv);

    // Textures are uploaded premultiplied (see TextureLoader), so filtered
    // samples average correctly over transparent pixels instead of bleeding
    // their meaningless RGB into edges. Divide the alpha back out so the
    // tint and the fixed-function blend behave as they would with straight
    // alpha; 1:1 nearest samples are restored to their original values.
    if (texColour.a == 0.0) {
        discard;
    }
    texColour.rgb /= texColour.a;

    colour = texColour * varying_colour;

    // This is required for stencil writing, since alpha testing is gone in OpenGL core.
    if (colour.a == 0) {
        discard;
    }
}
