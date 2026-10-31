#version 320 es
/*
 * Copyright (C) 2016 Sony Mobile Communications Inc.
 * All rights, including trade secret rights, reserved.
 */

precision mediump float;
layout(vertices = 8) out;
flat in int vsInstanceId[];
flat out int tcsInstanceId[];

void main() {
    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
    tcsInstanceId[gl_InvocationID] = vsInstanceId[gl_InvocationID];

    if (gl_InvocationID == 0) {
        gl_TessLevelOuter[0] = 50.0;
        gl_TessLevelOuter[1] = 1.0;
    }
}