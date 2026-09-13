package net.fireboy.aerocloakingcore.mixin.client;

import net.fireboy.aerocloakingcore.network.CloakingClient;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Iterator;

@Mixin(
        targets = "dev.ryanhcode.sable.sublevel.render.dispatcher.FancySubLevelRenderDispatcher"
)
public abstract class FancySubLevelRenderDispatcherMixin {

    @ModifyVariable(
            method = "renderSectionLayer",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> aerocloakingcore$filterSectionRender(
            Iterable<ClientSubLevel> subLevels
    ) {
        return filter(subLevels);
    }

    @ModifyVariable(
            method = "renderAfterSections",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> aerocloakingcore$filterAfterSections(
            Iterable<ClientSubLevel> subLevels
    ) {
        return filter(subLevels);
    }

    @ModifyVariable(
            method = "renderBlockEntities",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> aerocloakingcore$filterBlockEntities(
            Iterable<ClientSubLevel> subLevels
    ) {
        return filter(subLevels);
    }

    @ModifyVariable(
            method = "updateCulling",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> aerocloakingcore$filterCulling(
            Iterable<ClientSubLevel> subLevels
    ) {
        return filter(subLevels);
    }

    private static Iterable<ClientSubLevel> filter(
            Iterable<ClientSubLevel> subLevels
    ) {
        return () -> new Iterator<>() {

            private final Iterator<ClientSubLevel> original =
                    subLevels.iterator();

            private ClientSubLevel next;
            private boolean prepared = false;

            private void prepare() {
                if (prepared) {
                    return;
                }

                while (original.hasNext()) {
                    ClientSubLevel candidate = original.next();

                    if (!CloakingClient.shouldHideSubLevel(
                            candidate.getUniqueId()
                    )) {
                        next = candidate;
                        prepared = true;
                        return;
                    }
                }

                next = null;
                prepared = true;
            }

            @Override
            public boolean hasNext() {
                prepare();
                return next != null;
            }

            @Override
            public ClientSubLevel next() {
                prepare();

                ClientSubLevel result = next;

                prepared = false;
                next = null;

                return result;
            }
        };
    }
}