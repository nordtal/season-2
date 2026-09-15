package eu.nordtal.jcore.config.schema;

/**
 * What sits under a key, as the schema records it.
 * <p>
 * Deliberately the same three names steward-worker's own {@code ConfigEntry.Kind} uses for a
 * value read back out of a file - the two are meant to line up one for one, not to invent a
 * second vocabulary for the same idea.
 */
public enum SettingKind {
    /** A single value. */
    SCALAR,
    /** A sequence. */
    LIST,
    /** A nested mapping - a group, in the interface. */
    MAP
}
