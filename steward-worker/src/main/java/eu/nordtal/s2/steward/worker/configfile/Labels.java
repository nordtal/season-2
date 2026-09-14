package eu.nordtal.s2.steward.ui.configfile;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Turns a config key into the words a human reads above the input.
 *
 * <p>Mechanical on purpose. A table of nicer names ("Base URL", "Session length") would be a
 * second place to keep every key of every module in the stack up to date, and the one that is
 * wrong is always the one nobody remembers exists. What the key says is what the label says.</p>
 */
final class Labels {

    private Labels() {
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
