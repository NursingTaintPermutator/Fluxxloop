#version 320 es
/*
 * Copyright (C) 2016 Sony Mobile Communications Inc.
 * All rights, including trade secret rights, reserved.
 */
precision mediump float;
out vec4 color;
in float vAlpha;
in float vPhase;
in vec4 vPosition;
in vec3 vNormal;
flat in int gsInstanceId;
flat in int gsInvocationId;

uniform mat4 uVPMatrix;
uniform float uWidth[6];
uniform vec4 uColorA[6];
uniform vec4 uColorB[6];

const vec4 cLightA = vec4(0.0, 25.0, 20.0, 0.0);
const vec4 cLightB = vec4(25.0, -25.0, 25.0, 0.0);

vec2 calcLighting(vec4 lightPos) {
    vec3 lv = normalize(vPosition.xyz - lightPos.xyz);
    float dist = min(length(vPosition.xyz - lightPos.xyz), 1.0);
    float lightc = (dist * 0.25) + pow(dist, 2.0) * 0.5;
    float diffuse = abs(dot(lv, vNormal)) / lightc;
    vec3 view = normalize(-vPosition.xyz);
    vec3 refv = reflect(-lv, vNormal);
    float specular = pow(abs(dot(view, refv)), 10.0f) / lightc;

    return vec2(diffuse, specular);
}

void main() {
    float phase = abs(vPhase - 0.5) * 2.0;
    vec2 lightA = calcLighting(cLightA * uVPMatrix);
    vec2 lightB = calcLighting(cLightB * uVPMatrix);

    vec4 tmpColor = mix(uColorA[gsInstanceId], uColorB[gsInstanceId], phase);

    color.rgb = tmpColor.rgb * lightA.x + tmpColor.rgb * lightA.y;
    color.rgb += tmpColor.rgb * lightB.x + tmpColor.rgb * lightB.y;
    color.rgb += tmpColor.rgb * 0.2; // ambient
    color.rgb /= max(pow(vPosition.z + 5.0, 2.0) * 0.025, 1.0);
    if (gsInvocationId == 0) {
        float glow = smoothstep(0.6, 0.8, color.r * 0.299 + color.g * 0.587 + color.b * 0.114);
        if (glow < 0.05) discard;
        color.a = clamp(vAlpha * uWidth[gsInstanceId] * 20.0 / max(pow(vPosition.z + 4.0, 2.0) * 0.5, 1.0), 0.0, 1.0); // Anti-aliasing and DoF blur
        color.a /= max(pow(vPosition.z + 5.0, 2.0) * 0.5, 1.0);
        color.a *= tmpColor.a;
        color.a *= glow;
    } else {
        color.a = clamp(vAlpha * uWidth[gsInstanceId] * 20.0 / max(pow(vPosition.z + 4.0, 2.0) * 0.5, 1.0), 0.0, 1.0); // Anti-aliasing and DoF blur
        color.a /= max(pow(vPosition.z + 5.0, 2.0) * 0.5, 1.0);
        color.a *= tmpColor.a;
    }
}