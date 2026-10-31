#version 320 es
/*
 * Copyright (C) 2016 Sony Mobile Communications Inc.
 * All rights, including trade secret rights, reserved.
 */

precision mediump float;
layout(lines, invocations = 2) in;
layout(triangle_strip, max_vertices = 6) out;

out float vAlpha;
out float vPhase;
out vec4 vPosition;
out vec3 vNormal;
in float tevPhase0[];
in float tevPhase1[];
in vec4 tepAfter[];

flat in int tesInstanceId[];
flat out int gsInstanceId;
flat out int gsInvocationId;

uniform mat4 uVPMatrix;

const vec4 cLightA = vec4(0.0, 25.0, 20.0, 0.0);
const vec4 cLightB = vec4(25.0, -25.0, 25.0, 0.0);

vec2 calcLighting(vec4 vPosition, vec4 lightPos, vec3 vNormal) {
    vec3 lv = normalize(vPosition.xyz - lightPos.xyz);
    float dist = min(length(vPosition.xyz - lightPos.xyz), 1.0);
    float lightc = (dist * 0.25) + pow(dist, 2.0) * 0.5;
    float diffuse = abs(dot(lv, vNormal)) / lightc;
    vec3 view = normalize(-vPosition.xyz);
    vec3 refv = reflect(-lv, vNormal);
    float specular = pow(abs(dot(view, refv)), 10.0f) / lightc;

    return vec2(diffuse, specular);
}

void createVertex(vec4 position, float alpha, float phase, vec3 normal) {
    gl_Position = position;
    vAlpha = alpha;
    vPhase = phase;
    vPosition = position;
    vNormal = normal;
    gsInstanceId = tesInstanceId[0];
    gsInvocationId = gl_InvocationID;
    EmitVertex();
}

vec3 calcNormal(vec4 a, vec4 b, vec4 c) {
    return normalize(cross(b.xyz - a.xyz, c.xyz - a.xyz));
}

void main() {
    vec4 p0 = gl_in[0].gl_Position;
    vec4 p1 = gl_in[1].gl_Position;

    vec4 a, b, c, d, e, f;
    float centerAlpha;

    a = p0;
    b = tepAfter[0];
    c = (p0 + p1) / 2.0;
    d = (tepAfter[0] + tepAfter[1]) / 2.0;
    e = p1;
    f = tepAfter[1];
    if (gl_InvocationID == 0) {
        a.xy += (a.xy - c.xy) * 15.0;
        b.xy += (b.xy - d.xy) * 15.0;
        e.xy += (e.xy - c.xy) * 15.0;
        f.xy += (f.xy - d.xy) * 15.0;

        vec2 lv = max(calcLighting(a, cLightA * uVPMatrix, calcNormal(a, b, c)), calcLighting(a, cLightB * uVPMatrix, calcNormal(a, b, c)));
        if (lv.y < 0.025) return;

        centerAlpha = 0.035 * smoothstep(0.025, 1.0, lv.y);
    } else {
        a += (a - c) * pow(min(a.z + 5.0, 0.0), 2.0) * 0.4;
        b += (b - d) * pow(min(b.z + 5.0, 0.0), 2.0) * 0.4;
        e += (e - c) * pow(min(e.z + 5.0, 0.0), 2.0) * 0.4;
        f += (f - d) * pow(min(f.z + 5.0, 0.0), 2.0) * 0.4;

        centerAlpha = 1.0;
    }

    createVertex(a, 0.0, tevPhase0[0], normalize(calcNormal(a, b, c)));
    createVertex(b, 0.0, tevPhase1[0], normalize(calcNormal(b, c, a) + calcNormal(b, d, c)));
    createVertex(c, min(distance(c, d) * 5.0, centerAlpha), tevPhase0[0], normalize(calcNormal(c, a, b) + calcNormal(c, b, d) + calcNormal(c, d, e)));
    createVertex(d, min(distance(c, d) * 5.0, centerAlpha), tevPhase1[0], normalize(calcNormal(d, c, b) + calcNormal(d, e, c) + calcNormal(d, f, e)));
    createVertex(e, 0.0, tevPhase0[0], normalize(calcNormal(e, c, d) + calcNormal(e, d, f)));
    createVertex(f, 0.0, tevPhase1[0], normalize(calcNormal(f, e, d)));

    EndPrimitive();
}