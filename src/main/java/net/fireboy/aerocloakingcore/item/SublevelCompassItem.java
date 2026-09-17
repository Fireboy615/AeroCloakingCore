package net.fireboy.aerocloakingcore.item;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A compass that can be bound to a Sable sublevel.
 *
 * Shift-right-click a block on a sublevel to bind it. While the target
 * sublevel is loaded, the server refreshes the vanilla compass target to the
 * moving sublevel's current world-space centre. If it unloads, the compass
 * keeps the last known target and reports that the live signal is lost.
 */
public final class SublevelCompassItem extends CompassItem {

    private static final String TAG_LINKED_SUBLEVEL = "AeroLinkedSubLevel";
    private static final String TAG_LINKED_NAME = "AeroLinkedSubLevelName";
    private static final String TAG_SIGNAL_ACTIVE = "AeroLinkedSignalActive";

    /** Four updates per second is smooth enough for the vanilla compass wobble. */
    private static final int TARGET_UPDATE_INTERVAL_TICKS = 5;

    public SublevelCompassItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();

        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        SubLevel subLevel = findSubLevelForBinding(context);
        if (subLevel == null) {
            return InteractionResult.PASS;
        }

        if (!context.getLevel().isClientSide) {
            bind(
                    context.getItemInHand(),
                    subLevel
            );

            String targetName = displayTargetName(subLevel);
            player.displayClientMessage(
                    Component.literal("Compass linked: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(
                                    Component.literal(targetName)
                                            .withStyle(ChatFormatting.AQUA)
                            ),
                    true
            );
        }

        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public void inventoryTick(
            ItemStack stack,
            Level level,
            Entity entity,
            int itemSlot,
            boolean isSelected
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (entity.tickCount % TARGET_UPDATE_INTERVAL_TICKS != 0) {
            return;
        }

        UUID linkedId = getLinkedSubLevelId(stack);
        if (linkedId == null) {
            return;
        }

        SubLevel target = findLoadedSubLevel(
                serverLevel.getServer(),
                linkedId
        );

        if (target == null || target.isRemoved()) {
            setSignalActive(stack, false);
            return;
        }

        updateTrackerTarget(stack, target);
        updateStoredName(stack, target.getName());
        setSignalActive(stack, true);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltipComponents,
            TooltipFlag tooltipFlag
    ) {
        UUID linkedId = getLinkedSubLevelId(stack);

        if (linkedId == null) {
            tooltipComponents.add(
                    Component.literal("Unlinked")
                            .withStyle(ChatFormatting.GRAY)
            );
            tooltipComponents.add(
                    Component.literal("Shift-right-click a sublevel to link")
                            .withStyle(ChatFormatting.DARK_GRAY)
            );
            return;
        }

        String storedName = getStoredName(stack);
        String targetLabel = storedName != null && !storedName.isBlank()
                ? storedName
                : shortId(linkedId);

        tooltipComponents.add(
                Component.literal("Linked: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(
                                Component.literal(targetLabel)
                                        .withStyle(ChatFormatting.AQUA)
                        )
        );

        if (isSignalActive(stack)) {
            tooltipComponents.add(
                    Component.literal("Live tracking")
                            .withStyle(ChatFormatting.GREEN)
            );
        } else {
            tooltipComponents.add(
                    Component.literal("Signal lost - pointing to last known position")
                            .withStyle(ChatFormatting.GOLD)
            );
        }
    }

    /**
     * CompassItem normally renames any stack with a lodestone target to the
     * vanilla Lodestone Compass name. Keep our own item name instead.
     */
    @Override
    public String getDescriptionId(ItemStack stack) {
        return "item.aerocloakingcore.sublevel_compass";
    }

    public static UUID getLinkedSubLevelId(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return null;
        }

        CompoundTag tag = customData.copyTag();
        return tag.hasUUID(TAG_LINKED_SUBLEVEL)
                ? tag.getUUID(TAG_LINKED_SUBLEVEL)
                : null;
    }

    private static SubLevel findSubLevelForBinding(UseOnContext context) {
        SubLevel subLevel = Sable.HELPER.getContaining(
                context.getLevel(),
                context.getClickedPos()
        );

        Player player = context.getPlayer();
        if (subLevel == null && player != null) {
            subLevel = Sable.HELPER.getContaining(player);
        }

        if (subLevel == null && player != null) {
            subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        }

        return subLevel;
    }

    private static void bind(ItemStack stack, SubLevel subLevel) {
        UUID id = subLevel.getUniqueId();
        String name = subLevel.getName();

        CustomData.update(
                DataComponents.CUSTOM_DATA,
                stack,
                tag -> {
                    tag.putUUID(TAG_LINKED_SUBLEVEL, id);
                    tag.putBoolean(TAG_SIGNAL_ACTIVE, true);

                    if (name != null && !name.isBlank()) {
                        tag.putString(TAG_LINKED_NAME, name);
                    } else {
                        tag.remove(TAG_LINKED_NAME);
                    }
                }
        );

        updateTrackerTarget(stack, subLevel);
    }

    private static void updateTrackerTarget(
            ItemStack stack,
            SubLevel subLevel
    ) {
        BoundingBox3dc bounds = subLevel.boundingBox();

        BlockPos centre = BlockPos.containing(
                (bounds.minX() + bounds.maxX()) * 0.5,
                (bounds.minY() + bounds.maxY()) * 0.5,
                (bounds.minZ() + bounds.maxZ()) * 0.5
        );

        GlobalPos globalPos = GlobalPos.of(
                subLevel.getLevel().dimension(),
                centre
        );

        LodestoneTracker next = new LodestoneTracker(
                Optional.of(globalPos),
                false
        );

        LodestoneTracker current = stack.get(DataComponents.LODESTONE_TRACKER);
        if (!next.equals(current)) {
            stack.set(DataComponents.LODESTONE_TRACKER, next);
        }
    }

    private static SubLevel findLoadedSubLevel(
            MinecraftServer server,
            UUID subLevelId
    ) {
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }

            SubLevel subLevel = container.getSubLevel(subLevelId);
            if (subLevel != null) {
                return subLevel;
            }
        }

        return null;
    }

    private static void setSignalActive(
            ItemStack stack,
            boolean active
    ) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }

        CompoundTag existing = data.copyTag();
        if (existing.getBoolean(TAG_SIGNAL_ACTIVE) == active) {
            return;
        }

        CustomData.update(
                DataComponents.CUSTOM_DATA,
                stack,
                tag -> tag.putBoolean(TAG_SIGNAL_ACTIVE, active)
        );
    }

    private static boolean isSignalActive(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null
                && data.copyTag().getBoolean(TAG_SIGNAL_ACTIVE);
    }

    private static void updateStoredName(
            ItemStack stack,
            String name
    ) {
        String existing = getStoredName(stack);
        String normalized = name != null && !name.isBlank()
                ? name
                : null;

        if ((existing == null && normalized == null)
                || (existing != null && existing.equals(normalized))) {
            return;
        }

        CustomData.update(
                DataComponents.CUSTOM_DATA,
                stack,
                tag -> {
                    if (normalized == null) {
                        tag.remove(TAG_LINKED_NAME);
                    } else {
                        tag.putString(TAG_LINKED_NAME, normalized);
                    }
                }
        );
    }

    private static String getStoredName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }

        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAG_LINKED_NAME)) {
            return null;
        }

        return tag.getString(TAG_LINKED_NAME);
    }

    private static String displayTargetName(SubLevel subLevel) {
        String name = subLevel.getName();
        return name != null && !name.isBlank()
                ? name
                : shortId(subLevel.getUniqueId());
    }

    private static String shortId(UUID id) {
        String value = id.toString();
        return value.substring(0, Math.min(8, value.length()));
    }
}
