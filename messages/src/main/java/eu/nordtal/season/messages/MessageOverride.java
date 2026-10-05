package eu.nordtal.season.messages;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One text an admin stored for a key of a packaged bundle, in one language, as one of the key's variants.
 *
 * @param bundle   the packaged bundle, such as {@code smp}, the directory under {@code messages/} in the jar
 * @param variant  which of the key's texts, from 0; the override's variants replace the packaged ones together
 * @param replaced the {@link PackagedTexts#hash} of the packaged texts it replaced, {@code null} where there were none
 */
public record MessageOverride(
        String bundle,
        String key,
        String language,
        int variant,
        String text,
        @Nullable String replaced) {

    public MessageOverride {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(text, "text");
        if (variant < 0) {
            throw new IllegalArgumentException("a variant counts from 0, not " + variant);
        }
    }

    /**
     * Whether a release changed the packaged texts underneath this override since it was written.
     *
     * @param packaged the texts the jar now ships for the key in this language, {@code null} or empty for none
     */
    public boolean staleOver(final @Nullable List<String> packaged) {
        final String now = packaged == null || packaged.isEmpty() ? null : PackagedTexts.hash(packaged);
        return !Objects.equals(replaced, now);
    }
}
