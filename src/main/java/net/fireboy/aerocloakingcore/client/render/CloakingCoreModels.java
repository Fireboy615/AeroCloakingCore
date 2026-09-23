package net.fireboy.aerocloakingcore.client.render;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.fireboy.aerocloakingcore.AeroCloakingCore;

/** Flywheel partial models used by the animated Cloaking Core mirrors. */
public final class CloakingCoreModels {

    /**
     * Complete animated mirror assembly exported from Blockbench.
     *
     * This stays offset out in the chamber and rotates at half shaft speed.
     */
    public static final PartialModel MIRRORS = PartialModel.of(
            AeroCloakingCore.path("block/cloaking_core_mirrors")
    );

    /** Separate 2 px input shaft stub rendered at full Create shaft speed. */
    public static final PartialModel INPUT_SHAFT = PartialModel.of(
            AeroCloakingCore.path("block/cloaking_core_input_shaft")
    );

    private CloakingCoreModels() {
    }

    /** Forces this class (and its PartialModel) to initialize early. */
    public static void init() {
    }
}
