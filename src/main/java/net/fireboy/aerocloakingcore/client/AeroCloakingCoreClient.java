package net.fireboy.aerocloakingcore.client;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only mod entry point.
 */
@Mod(value = AeroCloakingCore.MOD_ID, dist = Dist.CLIENT)
public final class AeroCloakingCoreClient {

    public AeroCloakingCoreClient(ModContainer container) {
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                ConfigurationScreen::new
        );
    }
}
