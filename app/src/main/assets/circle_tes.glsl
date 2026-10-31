#version 320 es
/*
 * Copyright (C) 2016 Sony Mobile Communications Inc.
 * All rights, including trade secret rights, reserved.
 */

precision mediump float;
layout(isolines, equal_spacing) in;
uniform mat4 uVPMatrix;
uniform mat4 uTransM[6];
uniform float uWidth[6];
uniform float uAnimPhase[6];
uniform vec4 uTouchPos;
flat in int tcsInstanceId[];
flat out int tesInstanceId;
out float tevPhase0;
out float tevPhase1;
out vec4 tepAfter;

vec2 calcBezier(vec2 p0, vec2 p1, vec2 p2, vec2 p3, float t) {
    float tr = 1.0 - t;
    return (tr * tr * tr * p0)
          + (3.0 * t * tr * tr * p1)
          + (3.0 * t * t * tr * p2)
          + (t * t * t * p3);
}

void main()
{
    float w = 1.0 + (gl_TessCoord.x * uWidth[tcsInstanceId[0]] * 0.1);
    vec2 p0 = mix(gl_in[0].gl_Position.xy, gl_in[4].gl_Position.xy, uAnimPhase[tcsInstanceId[0]]) * vec2(w);
    vec2 p1 = mix(gl_in[1].gl_Position.xy, gl_in[5].gl_Position.xy, uAnimPhase[tcsInstanceId[0]]) * vec2(w);
    vec2 p2 = mix(gl_in[2].gl_Position.xy, gl_in[6].gl_Position.xy, uAnimPhase[tcsInstanceId[0]]) * vec2(w);
    vec2 p3 = mix(gl_in[3].gl_Position.xy, gl_in[7].gl_Position.xy, uAnimPhase[tcsInstanceId[0]]) * vec2(w);

    float t = gl_TessCoord.y;
    float ta = gl_TessCoord.y + (1.0 / gl_TessLevelOuter[0]);

    tesInstanceId = tcsInstanceId[0];
    gl_Position = uVPMatrix * uTransM[tcsInstanceId[0]] * vec4(calcBezier(p0, p1, p2, p3, t), gl_in[0].gl_Position.zw);
    tepAfter = uVPMatrix * uTransM[tcsInstanceId[0]] * vec4(calcBezier(p0, p1, p2, p3, ta), gl_in[0].gl_Position.zw);

    vec4 tpEffective;
    tpEffective = uVPMatrix * vec4(uTouchPos.xy, 0.0, 0.0);

    if (uTouchPos.z > 0.0) {
        vec4 c = uTransM[tcsInstanceId[0]][3];
        float v = max(uTouchPos.z * (1.0 - (uVPMatrix * vec4(-c.x, c.y, 13.0-c.z, 0.0)).z * 0.3), 0.0);
        vec2 tmpZ = vec2(gl_Position.z, tepAfter.z);
        gl_Position += (normalize(tpEffective - gl_Position) / pow(distance(tpEffective, gl_Position) * 0.044, 2.0)) * v;
        tepAfter += (normalize(tpEffective - tepAfter) / pow(distance(tpEffective, tepAfter) * 0.044, 2.0)) * v;
        vec4 tpEffective2a = tpEffective + normalize(tpEffective - gl_Position) / pow(distance(tpEffective, gl_Position) * 0.033, 2.0);
        vec4 tpEffective2b = tpEffective + normalize(tpEffective - tepAfter) / pow(distance(tpEffective, tepAfter) * 0.033, 2.0);
        gl_Position += (normalize(tpEffective2a - gl_Position) / pow(distance(tpEffective2a, gl_Position) * 0.044, 4.0)) * v;
        tepAfter += (normalize(tpEffective2b - tepAfter) / pow(distance(tpEffective2b, tepAfter) * 0.044, 4.0)) * v;
        gl_Position.z = tmpZ.x;
        tepAfter.z = tmpZ.y;
    }

    tevPhase0 = t;
    tevPhase1 = ta;
}
