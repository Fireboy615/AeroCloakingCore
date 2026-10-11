package net.fireboy.aerocloakingcore.ponder;

import net.fireboy.aerocloakingcore.AeroCloakingCore;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;

import net.minecraft.resources.ResourceLocation;

public final class AeroCloakingCorePonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return AeroCloakingCore.MOD_ID;
    }

    @Override
    public void registerScenes(
            PonderSceneRegistrationHelper<ResourceLocation> helper
    ) {
        helper.addStoryBoard(
                AeroCloakingCore.path("cloaking_core"),
                ResourceLocation.fromNamespaceAndPath("create", "creative_motor"),
                AeroCloakingCorePonderScenes::cloakingCore
        );
    }
}
