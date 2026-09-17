#version 150

#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
flat in int AeroParticleCloakData;

out vec4 fragColor;

float aeroParticleDither(vec2 position) {
    return fract(
        52.9829189 *
        fract(
            dot(
                position,
                vec2(0.06711056, 0.00583715)
            )
        )
    );
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;

    /*
     * Preserve vanilla's particle cutout using the particle's ORIGINAL alpha.
     * Cloak alpha is applied afterwards, so high cloak strengths still produce
     * genuinely low opacity instead of snapping out at vanilla's 0.1 cutoff.
     */
    if (color.a < 0.1) {
        discard;
    }

    int strengthCode = AeroParticleCloakData & 127;

    if (strengthCode != 0) {
        float cloakStrength = float(strengthCode) / 127.0;
        bool useDither = (AeroParticleCloakData & 128) != 0;

        if (useDither) {
            float threshold = aeroParticleDither(floor(gl_FragCoord.xy));

            if (threshold < cloakStrength) {
                discard;
            }
        } else {
            color.a *= 1.0 - cloakStrength;
        }
    }

    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
