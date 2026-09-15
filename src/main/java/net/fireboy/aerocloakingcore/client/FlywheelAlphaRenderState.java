package net.fireboy.aerocloakingcore.client;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small client-render state bridge for Flywheel ALPHA cloaking.
 *
 * <p>Sable updates embedded Flywheel environments during Flywheel's frame
 * preparation.  Those updates can happen on Flywheel worker threads, so the
 * "an alpha cloak exists this frame" bit is atomic.  The late-pass bit is
 * render-thread only.</p>
 */
public final class FlywheelAlphaRenderState {

    private static final AtomicBoolean ALPHA_ACTIVE_THIS_FRAME =
            new AtomicBoolean(false);

    private static volatile boolean lateAlphaPass;

    private FlywheelAlphaRenderState() {
    }

    public static void beginFrame() {
        ALPHA_ACTIVE_THIS_FRAME.set(false);
        lateAlphaPass = false;
    }

    public static void markAlphaActive() {
        ALPHA_ACTIVE_THIS_FRAME.set(true);
    }

    public static boolean isAlphaActive() {
        return ALPHA_ACTIVE_THIS_FRAME.get();
    }

    public static boolean isLateAlphaPass() {
        return lateAlphaPass;
    }

    public static void setLateAlphaPass(boolean late) {
        lateAlphaPass = late;
    }
}
