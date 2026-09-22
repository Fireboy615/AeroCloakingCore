package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;

import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Minecraft;

/**
 * Adds an invisible cloaked-sublevel hull to the main depth buffer temporarily.
 *
 * <p>The caller is responsible for using the mask only for a tightly bounded
 * draw phase. The depth present before {@link #begin()} is restored verbatim by
 * {@link #end()}.</p>
 *
 * <p>OCCLUDED_ONLY now uses this at the very end of the world render, after
 * water/Flywheel/partial cloak colour have already finished. That means the
 * restore can no longer erase depth that later world passes depend on.</p>
 */
public final class EntityOcclusionDepthMask {

    private static TextureTarget depthBackup;
    private static boolean active;

    private EntityOcclusionDepthMask() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void begin() {
        if (active
                || !CloakingClient.usesEntityOcclusionMask()
                || !AlphaSubLevelRenderQueue.hasEntityOcclusionDepth()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();

        ensureBackup(main.width, main.height);
        if (depthBackup == null) {
            return;
        }

        // Save the fully rendered world's depth before adding the invisible hull.
        depthBackup.copyDepthFrom(main);
        main.bindWrite(false);

        AlphaSubLevelRenderQueue.renderEntityOcclusionDepthPrepass();
        main.bindWrite(false);
        RenderSystem.depthMask(true);

        active = true;
    }

    public static void end() {
        if (!active) {
            return;
        }

        try {
            Minecraft minecraft = Minecraft.getInstance();
            RenderTarget main = minecraft.getMainRenderTarget();

            if (depthBackup != null) {
                main.copyDepthFrom(depthBackup);
                main.bindWrite(false);
            }
        } finally {
            RenderSystem.depthMask(true);
            active = false;
        }
    }

    private static void ensureBackup(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        if (depthBackup == null) {
            depthBackup = new TextureTarget(
                    width,
                    height,
                    true,
                    Minecraft.ON_OSX
            );
            return;
        }

        if (depthBackup.width != width || depthBackup.height != height) {
            depthBackup.resize(width, height, Minecraft.ON_OSX);
        }
    }
}
