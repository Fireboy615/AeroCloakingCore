uniform float AeroCloakDitherStrength;

float aeroBurnerDither(vec2 position) {
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

/*
 * DITHER is shader-side. ALPHA is handled with constant-alpha blending around
 * the direct BufferUploader flame draw, so it no longer depends on a custom
 * alpha uniform being present in the Veil shader.
 */
void tail() {
    float ditherStrength = clamp(
        AeroCloakDitherStrength,
        0.0,
        1.0
    );

    if (ditherStrength > 0.0) {
        float threshold = aeroBurnerDither(floor(gl_FragCoord.xy));
        if (threshold < ditherStrength) {
            discard;
        }
    }
}
