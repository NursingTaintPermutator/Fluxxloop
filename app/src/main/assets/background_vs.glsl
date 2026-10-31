#version 320 es
/*
 * Copyright (C) 2016 Sony Mobile Communications Inc.
 * All rights, including trade secret rights, reserved.
 */

precision mediump float;
in vec2 inPosition;

void main() {
    gl_Position = vec4(inPosition, 1.0, 1.0);
}