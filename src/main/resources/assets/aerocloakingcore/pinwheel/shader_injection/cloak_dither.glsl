uniform float AeroCloakStrength;


/*
 * Stable screen-space dither value.
 *
 * Pixels are either rendered normally or discarded completely,
 * so normal depth and transparency behaviour is preserved.
 */
float aeroCloakDither(vec2 position) {

    return fract(
        52.9829189 *
        fract(
            dot(
                position,
                vec2(
                    0.06711056,
                    0.00583715
                )
            )
        )
    );
}


/*
 * Called at the beginning of the fragment shader.
 */
void head() {

    if (AeroCloakStrength > 0.0) {

        float threshold =
                aeroCloakDither(
                    floor(gl_FragCoord.xy)
                );

        float strength =
                clamp(
                    AeroCloakStrength,
                    0.0,
                    1.0
                );

        if (threshold < strength) {
            discard;
        }
    }
}