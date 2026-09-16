package net.fireboy.aerocloakingcore.client;

/**
 * Client-side rendering technique used for partial cloaking.
 */
public enum CloakRenderMode {
    DITHER,

    /** Existing layered alpha renderer. Kept for backwards compatibility. */
    ALPHA,

    /**
     * Depth-aware alpha renderer. Opaque/cutout terrain first writes only its
     * nearest depth, then the visible surface is alpha blended without writing
     * new depth. Translucent terrain is deliberately excluded from the depth
     * pre-pass so glass/water can still reveal geometry behind them.
     */
    ALPHA_SURFACE;

    public boolean isAlpha() {
        return this == ALPHA || this == ALPHA_SURFACE;
    }

    public boolean usesSurfaceDepthPrepass() {
        return this == ALPHA_SURFACE;
    }
}
