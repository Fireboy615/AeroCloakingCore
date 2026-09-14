package net.fireboy.aerocloakingcore;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class SimulatedTabIntegration {

    private static final String SIMULATED_MODID = "simulated";

    private static final String REGISTRATE_CLASS =
            "dev.simulated_team.simulated.registrate.SimulatedRegistrate";

    public static final ResourceLocation SECTION =
            AeroCloakingCore.path(AeroCloakingCore.MOD_ID);

    private static boolean resolved = false;
    private static Fields fields = null;

    private SimulatedTabIntegration() {
    }

    public static boolean isAvailable() {
        return resolve() != null;
    }

    @SuppressWarnings("unchecked")
    public static void add(
            ResourceLocation itemId,
            Supplier<? extends Item> itemSupplier
    ) {
        Fields resolvedFields = resolve();

        if (resolvedFields == null) {
            return;
        }

        try {
            List<Supplier<? extends Item>> tabItems =
                    (List<Supplier<? extends Item>>)
                            resolvedFields.tabItems.get(null);

            Map<ResourceLocation, ResourceLocation> itemToSection =
                    (Map<ResourceLocation, ResourceLocation>)
                            resolvedFields.itemToSection.get(null);

            tabItems.add(itemSupplier);
            itemToSection.put(itemId, SECTION);

            AeroCloakingCore.LOGGER.info(
                    "Added {} to the Simulated creative tab, section {}",
                    itemId,
                    SECTION
            );

        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroCloakingCore.LOGGER.warn(
                    "Could not add {} to the Simulated creative tab",
                    itemId,
                    e
            );

            fields = null;
        }
    }

    private static Fields resolve() {
        if (resolved) {
            return fields;
        }

        resolved = true;

        if (!ModList.get().isLoaded(SIMULATED_MODID)) {
            return null;
        }

        try {
            Class<?> registrateClass =
                    Class.forName(REGISTRATE_CLASS);

            Field tabItems =
                    registrateClass.getField("TAB_ITEMS");

            Field itemToSection =
                    registrateClass.getField("ITEM_TO_SECTION");

            if (!List.class.isAssignableFrom(tabItems.getType())) {
                return null;
            }

            if (!Map.class.isAssignableFrom(itemToSection.getType())) {
                return null;
            }

            fields = new Fields(
                    tabItems,
                    itemToSection
            );

            return fields;

        } catch (ReflectiveOperationException | RuntimeException e) {
            AeroCloakingCore.LOGGER.info(
                    "Simulated is present but its creative tab could not be reached",
                    e
            );

            return null;
        }
    }

    private static final class Fields {

        private final Field tabItems;
        private final Field itemToSection;

        private Fields(
                Field tabItems,
                Field itemToSection
        ) {
            this.tabItems = tabItems;
            this.itemToSection = itemToSection;
        }
    }
}