package eu.nordtal.jcore.config.schema;

/**
 * What a scalar looks like to YAML, as the schema records it.
 * <p>
 * Deliberately the same four names steward-worker's own {@code ConfigEntry.Type} uses - see
 * {@link SettingKind} for why that is not a coincidence. Unit and value range are deliberately
 * not part of this: both were proposed for the schema and rejected (steward/50).
 */
public enum SettingType {
    /** Free text, including a Java {@code enum} - it is written to YAML as a plain string. */
    STRING,
    /** A whole number. */
    INTEGER,
    /** A number with a fractional part. */
    DECIMAL,
    /** {@code true} or {@code false}. */
    BOOLEAN
}
