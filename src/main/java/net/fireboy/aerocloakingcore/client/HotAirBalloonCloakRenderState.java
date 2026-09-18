package net.fireboy.aerocloakingcore.client;

/**
 * Render-thread coordination for Aeronautics' hot-air balloon heat overlay.
 *
 * Aeronautics normally renders the heat volume after solid blocks.  ALPHA
 * cloaked sublevels are intentionally replayed much later by Aero Cloaking
 * Core, so their heat volume has to be replayed at the same late point or the
 * late ship geometry simply paints over it.
 */
public final class HotAirBalloonCloakRenderState {

    private static boolean lateAlphaPass;
    private static boolean lateAlphaRequested;

    private HotAirBalloonCloakRenderState() {
    }

    public static void beginFrame() {
        lateAlphaPass = false;
        lateAlphaRequested = false;
    }

    public static void requestLateAlphaPass() {
        lateAlphaRequested = true;
    }

    public static boolean needsLateAlphaPass() {
        return lateAlphaRequested;
    }

    public static boolean isLateAlphaPass() {
        return lateAlphaPass;
    }

    public static void beginLateAlphaPass() {
        lateAlphaPass = true;
    }

    public static void endLateAlphaPass() {
        lateAlphaPass = false;
        lateAlphaRequested = false;
    }
}
