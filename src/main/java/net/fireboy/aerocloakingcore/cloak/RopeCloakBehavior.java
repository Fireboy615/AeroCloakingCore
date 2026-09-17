package net.fireboy.aerocloakingcore.cloak;

/**
 * Controls how Simulated rope strands react when one or both endpoints are
 * attached to cloaked sublevels.
 */
public enum RopeCloakBehavior {
    /** Smoothly interpolate cloak strength from one endpoint to the other. */
    GRADIENT,

    /** Apply the strongest endpoint cloak strength to the entire rope. */
    INHERIT_STRONGEST
}
