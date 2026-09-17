package net.fireboy.aerocloakingcore.item;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Late-game proximity scanner for Sable sublevels.
 *
 * It intentionally provides no bearing, exact position, cloak state, or
 * per-target range. The only information returned is how many OTHER loaded
 * sublevels are within the scan radius.
 */
public final class SublevelScannerItem extends Item {

    public static final double SCAN_RADIUS_BLOCKS = 256.0;
    public static final int COOLDOWN_TICKS = 100;

    public SublevelScannerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level,
            Player player,
            InteractionHand usedHand
    ) {
        ItemStack stack = player.getItemInHand(usedHand);

        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }

        int count = countNearbySubLevels(serverLevel, player);
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        ChatFormatting countColour = count > 0
                ? ChatFormatting.GOLD
                : ChatFormatting.GREEN;

        player.displayClientMessage(
                Component.literal("Sublevel signatures: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(
                                Component.literal(Integer.toString(count))
                                        .withStyle(countColour)
                        )
                        .append(
                                Component.literal(
                                                "  |  Radius: "
                                                        + (int) SCAN_RADIUS_BLOCKS
                                                        + " blocks"
                                        )
                                        .withStyle(ChatFormatting.DARK_GRAY)
                        ),
                true
        );

        return InteractionResultHolder.consume(stack);
    }

    private static int countNearbySubLevels(
            ServerLevel level,
            Player player
    ) {
        double radius = SCAN_RADIUS_BLOCKS;

        BoundingBox3d queryBounds = new BoundingBox3d(
                player.getX() - radius,
                player.getY() - radius,
                player.getZ() - radius,
                player.getX() + radius,
                player.getY() + radius,
                player.getZ() + radius
        );

        SubLevel ownSubLevel = Sable.HELPER.getContaining(player);
        if (ownSubLevel == null) {
            ownSubLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        }

        UUID ownId = ownSubLevel != null
                ? ownSubLevel.getUniqueId()
                : null;

        Set<UUID> detected = new HashSet<>();

        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(
                level,
                queryBounds
        )) {
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }

            UUID id = subLevel.getUniqueId();
            if (id == null || id.equals(ownId)) {
                continue;
            }

            if (distanceToBoundsSqr(
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    subLevel.boundingBox()
            ) <= radius * radius) {
                detected.add(id);
            }
        }

        return detected.size();
    }

    private static double distanceToBoundsSqr(
            double x,
            double y,
            double z,
            BoundingBox3dc bounds
    ) {
        double nearestX = clamp(x, bounds.minX(), bounds.maxX());
        double nearestY = clamp(y, bounds.minY(), bounds.maxY());
        double nearestZ = clamp(z, bounds.minZ(), bounds.maxZ());

        double dx = x - nearestX;
        double dy = y - nearestY;
        double dz = z - nearestZ;

        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(
            double value,
            double min,
            double max
    ) {
        return Math.max(min, Math.min(max, value));
    }
}
