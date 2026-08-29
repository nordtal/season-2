package eu.nordtal.s2.common;

/**
 * The phases season 2 moves through. {@code network-control} owns the current value and every
 * other module reads it, so the ordering here is the network's routing order.
 */
public enum SeasonPhase {

    /** Players are held on the pack-install server until they have accepted the resource pack. */
    RESOURCE_PACK_INSTALL,

    /** The hunger games start event. */
    START_EVENT,

    /** The SMP proper. */
    SMP
}
