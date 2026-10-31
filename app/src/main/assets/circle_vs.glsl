#version 320 es
/*
 * Copyright (C) 2016 Sony Mobile Communications Inc.
 * All rights, including trade secret rights, reserved.
 */

precision mediump float;
in vec2 inPosition;
flat out int vsInstanceId;
const int VERTEX_PER_LOOP = 24;

void main() {
    gl_Position = vec4(inPosition, 0.0, 1.0);
    vsInstanceId = gl_VertexID / VERTEX_PER_LOOP;
}