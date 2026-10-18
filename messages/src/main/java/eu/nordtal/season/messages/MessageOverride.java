package eu.nordtal.season.messages;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One variant of a key's texts that an admin stored, in one language.
 *
 * @param bundle   the directory under {@code messages/} in the jar, such as {@code smp}
 * @param variant  the index of the text, from 0
 * @param replaced the {@link PackagedTexts#hash} of the packaged texts it replaced, or {@code null}
 *     if there were none
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
     * Returns whether the packaged texts differ from the ones this override replaced.
     *
     * @param packaged the key's packaged texts in this language, {@code null} or empty if none
     */
    public boolean staleOver(final @Nullable List<String> packaged) {
        final String now = packaged == null || packaged.isEmpty() ? null : PackagedTexts.hash(packaged);
        return !Objects.equals(replaced, now);
    }
}
