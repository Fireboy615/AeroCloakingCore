in float AeroParticleCloakStrength;

/*
 * Run after vanilla particle.fsh has sampled the texture and performed its
 * normal alpha cutoff. Applying cloak alpha here avoids vanilla's 0.1 cutoff
 * turning a smooth fade into a sudden disappearance near full cloak.
 */
void tail() {

    if (AeroParticleCloakStrength > 0.0) {
        float strength = clamp(
                AeroParticleCloakStrength,
                0.0,
                1.0
        );

        fragColor.a *= 1.0 - strength;
    }
}
