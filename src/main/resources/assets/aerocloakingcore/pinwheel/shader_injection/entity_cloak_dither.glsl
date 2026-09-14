uniform float AeroCloakStrength;


/*
 * Same stable screen-space pattern used by the block cloak.
 * No alpha is changed: a fragment either renders normally or is discarded.
 */
float aeroEntityCloakDither(vec2 position) {

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


void head() {

    if (AeroCloakStrength > 0.0) {

        float strength =
                clamp(
                    AeroCloakStrength,
                    0.0,
                    1.0
                );

        float threshold =
                aeroEntityCloakDither(
                    floor(gl_FragCoord.xy)
                );

        if (threshold < strength) {
            discard;
        }
    }
}
