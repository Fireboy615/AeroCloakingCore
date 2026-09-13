package net.fireboy.aerocloakingcore.network;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CloakingClient {

    private static final Set<UUID> CLOAKED_SUBLEVELS = new HashSet<>();

    private CloakingClient() {
    }

    public static void setCloakedSubLevels(List<UUID> ids) {
        CLOAKED_SUBLEVELS.clear();
        CLOAKED_SUBLEVELS.addAll(ids);
    }

    public static boolean isCloaked(UUID subLevelId) {
        return CLOAKED_SUBLEVELS.contains(subLevelId);
    }

    /**
     * Returns the sublevel the local player is currently aboard/tracking.
     */
    public static SubLevel getViewerSubLevel() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return null;
        }

        return Sable.HELPER.getTrackingOrVehicleSubLevel(
                minecraft.player
        );
    }

    /**
     * Determines whether the given sublevel should be invisible
     * to the local player.
     */
    public static boolean shouldHideSubLevel(UUID subLevelId) {

        if (!isCloaked(subLevelId)) {
            return false;
        }

        SubLevel viewerSubLevel = getViewerSubLevel();

        // The viewer is aboard the cloaked vehicle.
        // Therefore they are allowed to see it.
        if (viewerSubLevel != null
                && subLevelId.equals(viewerSubLevel.getUniqueId())) {

            return false;
        }

        // Viewer is outside.
        return true;
    }

    /**
     * Determines whether an entity aboard a cloaked sublevel
     * should be invisible to the local player.
     */
    public static boolean shouldHidePlayer(Player target) {

        SubLevel targetSubLevel =
                Sable.HELPER.getTrackingOrVehicleSubLevel(target);

        if (targetSubLevel == null) {
            return false;
        }

        UUID targetId = targetSubLevel.getUniqueId();

        if (!isCloaked(targetId)) {
            return false;
        }

        SubLevel viewerSubLevel = getViewerSubLevel();

        // Both players are aboard the same cloaked vehicle.
        if (viewerSubLevel != null
                && targetId.equals(viewerSubLevel.getUniqueId())) {

            return false;
        }

        return true;
    }
}