package eu.nordtal.season.spec.schema;

/** The type of a scalar value in the schema. */
public enum SettingType {
    /** Free text, including an {@code enum}, stored as a string. */
    STRING,
    /** A whole number. */
    INTEGER,
    /** A number with a fraction. */
    DECIMAL,
    /** {@code true} or {@code false}. */
    BOOLEAN
}
