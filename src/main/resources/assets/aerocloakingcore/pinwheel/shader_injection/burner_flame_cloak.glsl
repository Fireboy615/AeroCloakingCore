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
 * DITHER runs at the start of the fragment shader so every flame fragment is
 * rejected with the same screen-space mask as the dithered sublevel hull.
 * ALPHA is handled with constant-alpha blending around the direct flame draw.
 */
void head() {
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
