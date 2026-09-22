package net.fireboy.aerocloakingcore.cloak;

/**
 * Controls which distance metric proximity reveal uses.
 */
public enum CloakDistanceMode {
    /** Use the nearest cloaked sublevel bounding box only. */
    BOUNDING_BOX,

    /** Use the closest face of the nearest real non-air block. */
    CLOSEST_FACE
}
