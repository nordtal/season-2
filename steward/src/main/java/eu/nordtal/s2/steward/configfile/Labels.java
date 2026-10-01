package eu.nordtal.s2.steward.configfile;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a config key into the words a human reads above the input.
 *
 * Mechanical on purpose, like jcore's {@code SettingLabels.of}: no second list of names to keep current.
 */
final class Labels {

    private Labels() {}

    /** The abbreviations a config key here uses, written upper-case in a label. */
    private static final Set<String> ACRONYMS = Set.of(
            "api", "db", "gui", "http", "https", "id", "ip", "json", "jvm", "motd", "mspt", "pvp", "smp", "sql", "tps",
            "ttl", "ui", "url", "uri", "uuid", "xp");

    private static final Pattern SEPARATORS = Pattern.compile("[-_]+");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /**
     * Splits a key into words, so {@code base-url} becomes {@code Base URL}.
     *
     * @param key the leaf key, split on {@code -}, {@code _} and a change of case
     * @return the words, lowercased except for a known acronym, the first one capitalised
     */
    static String of(final String key) {
        final StringBuilder out = new StringBuilder(key.length() + 4);
        for (final String part : SEPARATORS.splitAsStream(key).toArray(String[]::new)) {
            // "HTTPServer" is HTTP and Server, but a key written all in capitals is one word.
            final boolean allCaps = part.equals(part.toUpperCase(Locale.ROOT));
            final String[] words = allCaps
                    ? new String[] {part}
                    : CAMEL_BOUNDARY.splitAsStream(part).toArray(String[]::new);
            for (final String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                final String lower = word.toLowerCase(Locale.ROOT);
                // Capitals inside a mixed-case key ("discordSRV") are one word.
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
