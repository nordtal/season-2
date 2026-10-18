package eu.nordtal.season.spec.schema;

/** The kind of value under a key in the schema. */
public enum SettingKind {
    /** A single value. */
    SCALAR,
    /** A sequence. */
    LIST,
    /** A nested mapping, shown as a group. */
    MAP
}
