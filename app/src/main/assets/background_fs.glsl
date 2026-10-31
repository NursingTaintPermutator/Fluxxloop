#version 320 es
/*
 * Copyright (C) 2016 Sony Mobile Communications Inc.
 * All rights, including trade secret rights, reserved.
 */

precision mediump float;
out vec4 color;

uniform vec4 uGradColor0A;
uniform float uGradP1A;
uniform vec4 uGradColor1A;
uniform float uGradP2A;
uniform vec4 uGradColor2A;
uniform float uGradP3A;
uniform vec4 uGradColor3A;
uniform vec4 uGradColor4A;

uniform vec4 uGradColor0B;
uniform float uGradP1B;
uniform vec4 uGradColor1B;
uniform float uGradP2B;
uniform vec4 uGradColor2B;
uniform float uGradP3B;
uniform vec4 uGradColor3B;
uniform vec4 uGradColor4B;
uniform int uOrientation;

uniform float uGradAnimationPhase;
uniform vec2 uScreenSize;

float getDither() {
    return sin(gl_FragCoord.x * 2.0) * sin(gl_FragCoord.y * 2.0) * 0.03 + 0.97;
}

void main() {
    vec3 bgColor;

    float p1 = mix(uGradP1A, uGradP1B, uGradAnimationPhase);
    float p2 = mix(uGradP2A, uGradP2B, uGradAnimationPhase);
    float p3 = mix(uGradP3A, uGradP3B, uGradAnimationPhase);
    vec4 c0 = mix(uGradColor0A, uGradColor0B, uGradAnimationPhase);
    vec4 c1 = mix(uGradColor1A, uGradColor1B, uGradAnimationPhase);
    vec4 c2 = mix(uGradColor2A, uGradColor2B, uGradAnimationPhase);
    vec4 c3 = mix(uGradColor3A, uGradColor3B, uGradAnimationPhase);
    vec4 c4 = mix(uGradColor4A, uGradColor4B, uGradAnimationPhase);

    float p;
    if (uOrientation == 0) {
        p = gl_FragCoord.y / uScreenSize.y;
    } else if (uOrientation == 1) {
        p = 1.0 - (gl_FragCoord.x / uScreenSize.x);
    } else if (uOrientation == 2) {
        p = 1.0 - (gl_FragCoord.y / uScreenSize.y);
    } else {
        p = gl_FragCoord.x / uScreenSize.x;
    }

    if (p > p1) {
        bgColor = mix(c1.xyz, c0.xyz, (p - p1) / (1.0 - p1));
    } else if (p > p2) {
        bgColor = mix(c2.xyz, c1.xyz, (p - p2) / (p1 - p2));
    } else if (p > p3) {
        bgColor = mix(c3.xyz, c2.xyz, (p - p3) / (p2 - p3));
    } else {
        bgColor = mix(c4.xyz, c3.xyz, p / p3);
    }
    bgColor = bgColor * getDither();

    color = vec4(bgColor, 1.0);
}