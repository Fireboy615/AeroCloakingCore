out float AeroParticleCloakStrength;

/*
 * Minecraft samples the light map using UV2 / 16. Aero Cloaking Core stores
 * an eight-bit cloak strength in the two low nibbles that vanilla ignores.
 */
void head() {

    int lowNibble = UV2.x & 15;
    int highNibble = UV2.y & 15;

    int encodedStrength =
            lowNibble |
            (highNibble << 4);

    AeroParticleCloakStrength =
            float(encodedStrength) / 255.0;
}
