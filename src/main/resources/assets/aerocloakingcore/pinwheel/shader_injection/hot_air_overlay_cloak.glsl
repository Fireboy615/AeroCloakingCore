uniform float AeroCloakDitherStrength;

float aeroHotAirDither(vec2 position) {
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
 * Run before Aeronautics' fragment shader body.
 *
 * hot_air_overlay.fsh has an early return for the horizontal faces. A tail()
 * injection therefore misses those fragments, which makes the dithered heat
 * volume look like its faces/culling are wrong. head() applies the exact same
 * screen-space discard to every face before either shader branch can return.
 */
void head() {
    float ditherStrength = clamp(
        AeroCloakDitherStrength,
        0.0,
        1.0
    );

    if (ditherStrength > 0.0) {
        float threshold = aeroHotAirDither(floor(gl_FragCoord.xy));
        if (threshold < ditherStrength) {
            discard;
        }
    }
}
