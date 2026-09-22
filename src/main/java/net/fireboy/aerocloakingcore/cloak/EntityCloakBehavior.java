package net.fireboy.aerocloakingcore.cloak;

/**
 * Controls how living/non-living entities interact with cloaked sublevels.
 */
public enum EntityCloakBehavior {
    /** Entities inherit the same viewer-specific cloak amount as the ship. */
    MATCH_SHIP,

    /** Entities stay visible unless cloaked ship blocks obstruct line of sight. */
    OCCLUDED_ONLY,

    /** Cloaking never changes entity visibility. */
    ALWAYS_VISIBLE
}
