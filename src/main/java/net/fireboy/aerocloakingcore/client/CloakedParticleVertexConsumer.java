package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Adds per-particle cloak data to Minecraft's normal PARTICLE vertex format.
 *
 * Minecraft samples the light map with UV2 / 16, so the low four bits of both
 * UV2 components do not affect lighting. Those eight otherwise-unused bits are
 * used as one compact cloak byte:
 *
 *   0          = no particle cloak
 *   bits 0..6  = cloak strength (0..127)
 *   bit 7      = 0 for either alpha mode, 1 for dither
 *
 * ALPHA and ALPHA_SURFACE intentionally share the same particle representation
 * because a particle is already a single camera-facing surface. The terrain
 * surface-depth pre-pass has no meaningful equivalent for a sprite particle.
 */
public final class CloakedParticleVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private final int encodedCloak;

    public CloakedParticleVertexConsumer(
            VertexConsumer delegate,
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
        this.delegate = delegate;

        int quantizedStrength = Math.max(
                0,
                Math.min(
                        127,
                        Math.round(cloakStrength * 127.0F)
                )
        );

        int ditherBit =
                renderMode == CloakRenderMode.DITHER
                        ? 0x80
                        : 0;

        this.encodedCloak =
                ditherBit
                        | quantizedStrength;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(
            int red,
            int green,
            int blue,
            int alpha
    ) {
        /*
         * Keep the particle's own alpha unchanged here.
         *
         * The shader applies cloak alpha AFTER vanilla's normal 0.1 particle
         * cutout test. That allows a particle at 95% cloak to really render at
         * ~5% opacity instead of being discarded early by vanilla.
         */
        delegate.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        int encodedU =
                (u & ~0xF)
                        | (encodedCloak & 0xF);

        int encodedV =
                (v & ~0xF)
                        | ((encodedCloak >>> 4) & 0xF);

        delegate.setUv2(encodedU, encodedV);
        return this;
    }

    @Override
    public VertexConsumer setNormal(
            float normalX,
            float normalY,
            float normalZ
    ) {
        delegate.setNormal(normalX, normalY, normalZ);
        return this;
    }
}
