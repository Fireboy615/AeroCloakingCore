package net.fireboy.aerocloakingcore.client;

/**
 * Implemented on Flywheel's EmbeddedEnvironment by our compatibility mixin.
 *
 * Sable gives each sublevel its own embedded Flywheel environment.  This tiny
 * interface lets the sublevel hook attach the local viewer's current signed
 * cloak signal to that environment without reaching into Flywheel internals
 * from the rest of the mod.
 */
public interface FlywheelCloakEmbedding {

    void aerocloakingcore$setFlywheelCloakStrength(float strength);
}
