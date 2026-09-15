package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;

/**
 * Client-side cache used by the Mods -> Config screen.
 *
 * The normal cloak sync keeps the values current. A dedicated config-snapshot
 * packet additionally tells the screen whether this player is allowed to edit
 * the server and whether the last save succeeded.
 */
public final class ServerConfigClientState {

    public record Snapshot(
            CloakingServerSettings settings,
            boolean permissionKnown,
            boolean canEdit,
            ServerConfigSnapshotPayload.Result result,
            long revision
    ) {
    }

    private static volatile Snapshot snapshot =
            new Snapshot(
                    CloakingServerSettings.DEFAULT,
                    false,
                    false,
                    ServerConfigSnapshotPayload.Result.NONE,
                    0L
            );

    private ServerConfigClientState() {
    }

    public static Snapshot get() {
        return snapshot;
    }

    /** Called from the ordinary cloak-state sync sent on login/config changes. */
    public static synchronized void updateSettings(
            CloakingServerSettings settings
    ) {
        Snapshot current = snapshot;

        snapshot = new Snapshot(
                settings != null
                        ? settings.normalized()
                        : CloakingServerSettings.DEFAULT,
                current.permissionKnown(),
                current.canEdit(),
                current.result(),
                current.revision() + 1L
        );
    }

    /** Called from the dedicated config editor response packet. */
    public static synchronized void apply(
            ServerConfigSnapshotPayload payload
    ) {
        Snapshot current = snapshot;

        snapshot = new Snapshot(
                payload.settings().normalized(),
                true,
                payload.canEdit(),
                payload.result(),
                current.revision() + 1L
        );
    }
}
