package eu.nordtal.jcore.config.schema;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * Turns a config key into the words a human reads above the input.
 * <p>
 * Deliberately the same mechanical rule steward-worker's own {@code Labels.of} applies to a raw
 * key it reads out of a file with no schema: split on {@code -}/{@code _} and a change of case,
 * lower-case all but a short list of acronyms, capitalise the first word. Keeping the two
 * identical means a setting reads the same whether the interface got its label from this schema or
 * from the raw-file fallback - two algorithms producing two different capitalisations of the same
 * key would be its own small inconsistency. jcore cannot
 * depend on steward-worker to share the one implementation, so this is the other, matching half.
 */
final class SettingLabels {

    private SettingLabels() {
    }

    /**
     * The few abbreviations a config key in this stack actually uses, written upper-case in a
     * label. A short list rather than "every word of two or three letters", which would turn
     * {@code max} and {@code day} into shouting.
     */
    private static final Set<String> ACRONYMS = Set.of(
            "api", "db", "gui", "http", "https", "id", "ip", "json", "jvm", "motd", "mspt", "pvp",
            "smp", "sql", "tps", "ttl", "ui", "url", "uri", "uuid", "xp");

    /**
     * {@code base-url} becomes {@code Base URL}, {@code stop_services} becomes
     * {@code Stop services}, {@code logFailedRequests} becomes {@code Log failed requests}.
     *
     * @param key the leaf key
     * @return the key split on {@code -}, {@code _} and a change of case, lowercased except for a
     *         known acronym, with the first word capitalised
     */
    static @NotNull String of(final @NotNull String key) {
        final StringBuilder out = new StringBuilder(key.length() + 4);
        for (final String part : key.split("[-_]+")) {
            // "HTTPServer" is HTTP and Server, "serverUuid" is server and Uuid. A key written all
            // in capitals is one word, not one word per letter.
            final boolean allCaps = part.equals(part.toUpperCase(Locale.ROOT));
            final String[] words = allCaps
                    ? new String[]{part}
                    : part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
            for (final String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                final String lower = word.toLowerCase(Locale.ROOT);
                // A word written in capitals inside a mixed-case key ("discordSRV") was meant as one.
                final boolean shouted = !allCaps && word.length() > 1
                        && word.equals(word.toUpperCase(Locale.ROOT))
                        && !word.equals(lower);
                out.append(ACRONYMS.contains(lower) || shouted ? lower.toUpperCase(Locale.ROOT) : lower);
            }
        }
        if (out.isEmpty()) {
            return key;
        }
        out.setCharAt(0, Character.toUpperCase(out.charAt(0)));
        return out.toString();
    }
}
