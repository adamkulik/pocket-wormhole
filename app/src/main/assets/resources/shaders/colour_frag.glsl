#version 330 core

in vec4 varying_colour;

out vec4 colour;

void main() {
    colour = varying_colour;
}
