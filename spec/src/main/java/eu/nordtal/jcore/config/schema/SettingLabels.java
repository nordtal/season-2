package eu.nordtal.jcore.config.schema;

import java.util.Locale;
import java.util.Set;

/**
 * Turns a config key into the words a human reads above the input.
 *
 * Deliberately the same mechanical rule steward-worker's own {@code Labels.of} applies to a raw
 * key it reads out of a file with no schema: split on {@code -}/{@code _} and a change of case,
 * lower-case all but a short list of acronyms, capitalise the first word. Keeping the two
 * identical means a setting reads the same whether the interface got its label from this schema or
 * from the raw-file fallback - two algorithms producing two different capitalisations of the same
 * key would be its own small inconsistency. jcore cannot
 * depend on steward-worker to share the one implementation, so this is the other, matching half.
 */
final class SettingLabels {

    private SettingLabels() {}

    /** The abbreviations a config key uses that stay upper-case in a label. */
    private static final Set<String> ACRONYMS = Set.of(
            "api", "db", "gui", "http", "https", "id", "ip", "json", "jvm", "motd", "mspt", "pvp", "smp", "sql", "tps",
            "ttl", "ui", "url", "uri", "uuid", "xp");

    /**
     * For example {@code base-url} becomes {@code Base URL} and {@code stop_services} becomes {@code Stop services}.
     *
     * @param key the leaf key
     * @return the key split on {@code -}, {@code _} and a change of case, lowercased except for a
     *         known acronym, with the first word capitalised
     */
    static String of(final String key) {
        final StringBuilder out = new StringBuilder(key.length() + 4);
        for (final String part : key.split("[-_]+", 0)) {
            // A key written all in capitals is one word, not one word per letter.
            final boolean allCaps = part.equals(part.toUpperCase(Locale.ROOT));
            final String[] words =
                    allCaps ? new String[] {part} : part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
            for (final String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                final String lower = word.toLowerCase(Locale.ROOT);
                // A word written in capitals inside a mixed-case key ("discordSRV") was meant as one.
                final boolean shouted = !allCaps
                        && word.length() > 1
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
