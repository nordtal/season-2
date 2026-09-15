package eu.nordtal.jcore.config.schema;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Turns a config key into the words a human reads above the input.
 * <p>
 * Deliberately the same mechanical rule steward-worker's own {@code Labels.of} applies to a raw
 * key it reads out of a file with no schema: split on {@code -}/{@code _}, lower-case, capitalise
 * the first word. Keeping the two identical means a setting reads the same whether the interface
 * got its label from this schema or from the raw-file fallback - two algorithms producing two
 * different capitalisations of the same key would be its own small inconsistency. jcore cannot
 * depend on steward-worker to share the one implementation, so this is the other, matching half.
 */
final class SettingLabels {

    private SettingLabels() {
    }

    /**
     * {@code base-url} becomes {@code Base url}, {@code stop_services} becomes
     * {@code Stop services}.
     *
     * @param key the leaf key
     * @return the key split on {@code -} and {@code _}, lowercased, with the first word capitalised
     */
    static @NotNull String of(final @NotNull String key) {
        final StringBuilder out = new StringBuilder(key.length());
        for (final String word : key.split("[-_]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word.toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) {
            return key;
        }
        out.setCharAt(0, Character.toUpperCase(out.charAt(0)));
        return out.toString();
    }
}
