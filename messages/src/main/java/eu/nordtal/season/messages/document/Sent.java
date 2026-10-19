package eu.nordtal.season.messages.document;

import java.util.Objects;

/** A document as the code sends it: its declaration and the values it fills it with. */
public record Sent<V extends Record>(DocumentSpec<V> document, V values) {

    public Sent {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(values, "values");
    }

    public static Sent<DocumentSpec.None> of(final DocumentSpec<DocumentSpec.None> document) {
        return new Sent<>(document, new DocumentSpec.None());
    }
}
