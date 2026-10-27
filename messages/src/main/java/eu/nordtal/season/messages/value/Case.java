package eu.nordtal.season.messages.value;

/**
 * The grammatical case a building block is shown in, which a text binds as {@code case=dative}.
 * A language without cases ignores it, and a text that binds none takes the case {@code other}.
 */
public enum Case {
    NOMINATIVE,
    ACCUSATIVE,
    DATIVE,
    GENITIVE
}
