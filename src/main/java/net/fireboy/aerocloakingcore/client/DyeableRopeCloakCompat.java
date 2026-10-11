package net.fireboy.aerocloakingcore.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.index.SimPartialModels;

import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional Dyeable Ropes compatibility for Aero's custom segmented rendering.
 *
 * When cloaking is active we replace Simulated's normal RopeStrandRenderer,
 * bypassing Dyeable Ropes' injection into that renderer. Use that mod's
 * greyscale partials and exact client strand tint instead, so the dye survives
 * both alpha and dither transitions.
 */
public final class DyeableRopeCloakCompat {
    private static boolean resolved;
    private static Method tintForHolder;
    private static PartialModel tintedRope;
    private static PartialModel tintedKnot;

    private DyeableRopeCloakCompat() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!ModList.get().isLoaded("dyeable_ropes")) {
            return;
        }

        try {
            Class<?> colors = Class.forName(
                    "dev.matejhozlar.dyeableropes.client.ClientDyedStrandColors"
            );
            tintForHolder = colors.getMethod(
                    "tintForHolder", RopeStrandHolderBehavior.class
            );

            Class<?> models = Class.forName(
                    "dev.matejhozlar.dyeableropes.client.DyeableRopesPartialModels"
            );
            Field rope = models.getField("ROPE");
            Field knot = models.getField("ROPE_KNOT");
            tintedRope = (PartialModel) rope.get(null);
            tintedKnot = (PartialModel) knot.get(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            // An incompatible optional mod should never disable cloaking.
            tintForHolder = null;
            tintedRope = null;
            tintedKnot = null;
        }
    }

    public static RopeAppearance appearance(RopeStrandHolderBehavior holder) {
        resolve();
        if (tintForHolder != null && tintedRope != null && tintedKnot != null) {
            try {
                int color = ((Number) tintForHolder.invoke(null, holder)).intValue();
                if (color != -1) {
                    return new RopeAppearance(tintedRope, tintedKnot, color);
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Fall back to uncolored Simulated rope geometry.
            }
        }

        return new RopeAppearance(
                SimPartialModels.ROPE, SimPartialModels.ROPE_KNOT, -1
        );
    }

    public record RopeAppearance(
            PartialModel rope, PartialModel knot, int tint
    ) {
        public boolean dyed() {
            return tint != -1;
        }
    }
}
