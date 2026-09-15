package net.fireboy.aerocloakingcore.client.render;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.fireboy.aerocloakingcore.AeroCloakingCore;

/**
 * Flywheel partial models used by the animated Cloaking Core rotor.
 *
 * These are deliberately loaded during client mod construction so Flywheel
 * can register them before Minecraft's model bake completes.
 */
public final class CloakingCoreModels {

    public static final PartialModel ROTOR_SHAFT = PartialModel.of(
            AeroCloakingCore.path("block/cloaking_core_rotor_shaft")
    );

    public static final PartialModel ROTOR_PANES = PartialModel.of(
            AeroCloakingCore.path("block/cloaking_core_rotor_panes")
    );

    private CloakingCoreModels() {
    }

    /** Forces this class (and its PartialModels) to initialize early. */
    public static void init() {
    }
}
