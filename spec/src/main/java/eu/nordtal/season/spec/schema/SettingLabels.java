package eu.nordtal.season.spec.schema;

import java.util.Locale;
import java.util.Set;

/**
 * Turns a setting key into the label above its input.
 * It splits the key at {@code -}, {@code _} and a change of case and keeps a few acronyms in capitals.
 */
final class SettingLabels {

    private SettingLabels() {}

    /** The abbreviations that a label writes in capitals. */
    private static final Set<String> ACRONYMS = Set.of(
            "api", "db", "gui", "http", "https", "id", "ip", "json", "jvm", "motd", "mspt", "pvp", "smp", "sql", "tps",
            "ttl", "ui", "url", "uri", "uuid", "xp");

    /**
     * Returns the label of a key.
     * For example, {@code base-url} gives {@code Base URL} and {@code stop_services} gives {@code Stop services}.
     *
     * @param key the leaf key
     * @return the key split at {@code -}, {@code _} and a change of case, in lowercase except for an acronym, with the
     * first word capitalised
     */
    static String of(final String key) {
        final StringBuilder out = new StringBuilder(key.length() + 4);
        for (final String part : key.split("[-_]+", 0)) {
            // A key in capitals is one word.
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
                // A word in capitals inside a mixed-case key ("discordSRV") is an acronym.
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
