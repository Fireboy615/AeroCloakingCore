package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Carries a per-particle cloak strength through Minecraft's normal particle
 * batch without changing the batch order.
 *
 * Vanilla light-map UVs are sampled as UV2 / 16, so the low four bits of each
 * component are ignored by lighting. Together those two nibbles give us eight
 * bits in which to store the cloak strength. The particle shader injection
 * recovers the value and multiplies the final fragment alpha after vanilla's
 * own alpha-discard test has already run.
 *
 * That last detail is important: particles can fade smoothly all the way down
 * instead of being abruptly discarded when the multiplied alpha falls below
 * vanilla's 0.1 particle cutoff.
 */
public final class CloakedParticleVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private final int encodedStrength;

    public CloakedParticleVertexConsumer(
            VertexConsumer delegate,
            float cloakStrength
    ) {
        this.delegate = delegate;
        this.encodedStrength = Math.max(
                0,
                Math.min(
                        255,
                        Math.round(cloakStrength * 255.0F)
                )
        );
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
        int encodedU = (u & ~0xF) | (encodedStrength & 0xF);
        int encodedV = (v & ~0xF) | ((encodedStrength >>> 4) & 0xF);

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
