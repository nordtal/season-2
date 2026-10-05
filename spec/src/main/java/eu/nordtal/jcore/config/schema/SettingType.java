package eu.nordtal.jcore.config.schema;

/** What a scalar's value looks like, as the schema records it. */
public enum SettingType {
    /** Free text, including a Java {@code enum}, stored as a plain string. */
    STRING,
    /** A whole number. */
    INTEGER,
    /** A number with a fractional part. */
    DECIMAL,
    /** {@code true} or {@code false}. */
    BOOLEAN
}
