package eu.nordtal.season.spec.schema;

/** What sits under a key, as the schema records it. */
public enum SettingKind {
    /** A single value. */
    SCALAR,
    /** A sequence. */
    LIST,
    /** A nested mapping, shown as a group. */
    MAP
}
