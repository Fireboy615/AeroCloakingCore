package net.fireboy.aerocloakingcore.redstone;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.ryanhcode.sable.Sable;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Create Redstone Link receiver used by the Cloaking Core.
 *
 * Important: Create's normal RedstoneLinkNetworkHandler.withinRange()
 * compares raw BlockPos values. Sable stores sublevel blocks in distant plot
 * coordinates, so that check is incorrect whenever one side of the link is
 * on a moving sublevel. We therefore perform the range test in Sable's
 * projected/global world space instead.
 */
public final class CloakingReceivingLink implements IRedstoneLinkable {

    private final BlockEntity blockEntity;

    private RedstoneLinkNetworkHandler.Frequency first =
            RedstoneLinkNetworkHandler.Frequency.EMPTY;

    private RedstoneLinkNetworkHandler.Frequency second =
            RedstoneLinkNetworkHandler.Frequency.EMPTY;

    private boolean registered;
    private int received;

    public CloakingReceivingLink(BlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    public boolean isConfigured() {
        return !first.getStack().isEmpty()
                || !second.getStack().isEmpty();
    }

    public void setFrequencies(ItemStack first, ItemStack second) {
        this.first = RedstoneLinkNetworkHandler.Frequency.of(first);
        this.second = RedstoneLinkNetworkHandler.Frequency.of(second);
    }

    public void addToNetwork(Level level) {
        if (registered || !isConfigured()) {
            return;
        }

        handler().addToNetwork(level, this);
        registered = true;
    }

    public void removeFromNetwork(Level level) {
        if (!registered) {
            return;
        }

        handler().removeFromNetwork(level, this);
        registered = false;
        received = 0;
    }

    /**
     * Reads the strongest transmitter currently reachable on this frequency.
     *
     * We intentionally do not use RedstoneLinkNetworkHandler.withinRange()
     * here because its raw BlockPos distance is not Sable-sublevel aware.
     */
    public int readNetwork(Level level) {
        if (!registered) {
            received = 0;
            return 0;
        }

        int strength = 0;

        for (IRedstoneLinkable link : handler().getNetworkOf(level, this)) {
            if (link == this || !link.isAlive()) {
                continue;
            }

            if (!withinSableAwareRange(level, this, link)) {
                continue;
            }

            strength = Math.max(
                    strength,
                    link.getTransmittedStrength()
            );

            if (strength >= 15) {
                break;
            }
        }

        received = strength;
        return strength;
    }

    public int getReceived() {
        return received;
    }

    /**
     * Performs Create's normal link-range rule, but after projecting both
     * positions out of any Sable sublevels into real world space.
     */
    private static boolean withinSableAwareRange(
            Level level,
            IRedstoneLinkable first,
            IRedstoneLinkable second
    ) {
        if (first == second) {
            return true;
        }

        Vec3 firstPos = Vec3.atCenterOf(first.getLocation());
        Vec3 secondPos = Vec3.atCenterOf(second.getLocation());

        double distanceSquared =
                Sable.HELPER.distanceSquaredWithSubLevels(
                        level,
                        firstPos,
                        secondPos
                );

        double range =
                AllConfigs.server().logistics.linkRange.get();

        return distanceSquared < range * range;
    }

    private static RedstoneLinkNetworkHandler handler() {
        return Create.REDSTONE_LINK_NETWORK_HANDLER;
    }

    @Override
    public int getTransmittedStrength() {
        return 0;
    }

    @Override
    public void setReceivedStrength(int strength) {
        received = strength;
    }

    @Override
    public boolean isListening() {
        return true;
    }

    @Override
    public boolean isAlive() {
        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();

        return level != null
                && !blockEntity.isRemoved()
                && level.isLoaded(pos)
                && level.getBlockEntity(pos) == blockEntity;
    }

    @Override
    public Couple<RedstoneLinkNetworkHandler.Frequency> getNetworkKey() {
        return Couple.create(first, second);
    }

    @Override
    public BlockPos getLocation() {
        return blockEntity.getBlockPos();
    }
}
