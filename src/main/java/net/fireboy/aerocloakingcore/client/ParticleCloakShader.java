package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.fireboy.aerocloakingcore.AeroCloakingCore;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Dedicated particle shader used by Aero Cloaking Core.
 *
 * Unlike the earlier shader-injection experiment, this is registered as a
 * normal NeoForge core shader and selected explicitly for Minecraft's built-in
 * particle batches. That gives the particle renderer a reliable per-vertex
 * cloak channel without changing the normal particle batching/order.
 */
@EventBusSubscriber(
        modid = AeroCloakingCore.MOD_ID,
        value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD
)
public final class ParticleCloakShader {

    @Nullable
    private static ShaderInstance shader;

    private ParticleCloakShader() {
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(
                                    AeroCloakingCore.MOD_ID,
                                    "particle_cloak"
                            ),
                            DefaultVertexFormat.PARTICLE
                    ),
                    loaded -> shader = loaded
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load Aero Cloaking Core particle shader",
                    exception
            );
        }
    }

    @Nullable
    public static ShaderInstance get() {
        return shader;
    }
}
