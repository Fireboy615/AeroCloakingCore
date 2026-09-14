package net.fireboy.aerocloakingcore.cloak;

import net.fireboy.aerocloakingcore.network.CloakingSyncPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.fireboy.aerocloakingcore.AeroCloakingCore;

@EventBusSubscriber(
        modid = AeroCloakingCore.MOD_ID
)
public final class CloakingManager {

    private static final Set<UUID> CLOAKED_SUBLEVELS =
            new HashSet<>();

    private CloakingManager() {
    }

    public static void addCloakedSubLevel(UUID subLevelId) {

        if (CLOAKED_SUBLEVELS.add(subLevelId)) {
            sync();
        }
    }

    public static void removeCloakedSubLevel(UUID subLevelId) {

        if (CLOAKED_SUBLEVELS.remove(subLevelId)) {
            sync();
        }
    }

    public static boolean isCloaked(UUID subLevelId) {
        return CLOAKED_SUBLEVELS.contains(subLevelId);
    }

    public static Set<UUID> getCloakedSubLevels() {
        return Collections.unmodifiableSet(
                CLOAKED_SUBLEVELS
        );
    }

    public static void sync() {

        PacketDistributor.sendToAllPlayers(
                new CloakingSyncPayload(
                        List.copyOf(CLOAKED_SUBLEVELS)
                )
        );
    }

    public static void syncToPlayer(ServerPlayer player) {

        PacketDistributor.sendToPlayer(
                player,
                new CloakingSyncPayload(
                        List.copyOf(CLOAKED_SUBLEVELS)
                )
        );
    }

    public static void clear() {

        if (!CLOAKED_SUBLEVELS.isEmpty()) {
            CLOAKED_SUBLEVELS.clear();
            sync();
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(
            PlayerEvent.PlayerLoggedInEvent event
    ) {

        if (event.getEntity() instanceof ServerPlayer player) {
            syncToPlayer(player);
        }
    }
}