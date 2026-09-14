package net.fireboy.aerocloakingcore.cloak;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.network.CloakingSyncPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = AeroCloakingCore.MOD_ID)
public final class CloakingManager {

    private static final Map<UUID, CloakingCoreSettings> CLOAKED_SUBLEVELS =
            new HashMap<>();

    private CloakingManager() {
    }

    public static void setCloakedSubLevel(
            UUID subLevelId,
            CloakingCoreSettings settings
    ) {
        CloakingCoreSettings normalized = settings.normalized();
        CloakingCoreSettings previous = CLOAKED_SUBLEVELS.put(
                subLevelId,
                normalized
        );

        if (!normalized.equals(previous)) {
            sync();
        }
    }

    public static void addCloakedSubLevel(UUID subLevelId) {
        setCloakedSubLevel(
                subLevelId,
                CloakingCoreSettings.DEFAULT.withCloakStrength(1.0F)
        );
    }

    public static void removeCloakedSubLevel(UUID subLevelId) {
        if (CLOAKED_SUBLEVELS.remove(subLevelId) != null) {
            sync();
        }
    }

    public static boolean isCloaked(UUID subLevelId) {
        return CLOAKED_SUBLEVELS.containsKey(subLevelId);
    }

    public static Map<UUID, CloakingCoreSettings> getCloakedSubLevels() {
        return Collections.unmodifiableMap(CLOAKED_SUBLEVELS);
    }

    private static CloakingSyncPayload createPayload() {
        List<CloakingSyncPayload.Entry> entries = CLOAKED_SUBLEVELS
                .entrySet()
                .stream()
                .map(entry -> new CloakingSyncPayload.Entry(
                        entry.getKey(),
                        entry.getValue()
                ))
                .toList();

        return new CloakingSyncPayload(
                CloakingServerSettings.fromConfig(),
                entries
        );
    }

    public static void sync() {
        PacketDistributor.sendToAllPlayers(createPayload());
    }

    public static void syncToPlayer(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, createPayload());
    }

    public static void clear() {
        if (!CLOAKED_SUBLEVELS.isEmpty()) {
            CLOAKED_SUBLEVELS.clear();
            sync();
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncToPlayer(player);
        }
    }
}
