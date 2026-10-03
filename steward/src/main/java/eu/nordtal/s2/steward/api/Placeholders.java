package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.internalapi.agent.MessageArg;
import eu.nordtal.s2.internalapi.agent.MessageEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The placeholder checks of a message save: an undeclared one refuses it, a dropped one warns. */
final class Placeholders {

    /** A placeholder, {@code {name}} or {@code <_name>}; a formatting tag such as {@code <bold>} is not one. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z0-9_.-]+}|<_[A-Za-z0-9_-]+>");

    private Placeholders() {}

    /**
     * The placeholders {@code edited} uses that {@code entry}'s schema does not declare, each once, in order.
     *
     * An entry the schema does not describe is never checked.
     */
    static List<String> unknown(final MessageEntry entry, final @Nullable String edited) {
        if (!entry.described()) {
            return List.of();
        }
        final Set<String> declared = new HashSet<>();
        for (final MessageArg arg : entry.args()) {
            declared.add(arg.token());
        }
        final List<String> unknown = new ArrayList<>();
        for (final String token : of(edited)) {
            if (!declared.contains(token) && !unknown.contains(token)) {
                unknown.add(token);
            }
        }
        return unknown;
    }

    /**
     * The placeholders {@code original} names that {@code edited} no longer does, each once, in its order.
     *
     * @param original the packaged text the admin started from
     */
    static List<String> missing(final @Nullable String original, final @Nullable String edited) {
        final Set<String> after = new HashSet<>(of(edited));
        final List<String> missing = new ArrayList<>();
        for (final String token : of(original)) {
            if (!after.contains(token) && !missing.contains(token)) {
                missing.add(token);
            }
        }
        return missing;
    }

    /** Every placeholder token in {@code text}, in the order it appears; {@code null} reads as none. */
    private static List<String> of(final @Nullable String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        final List<String> found = new ArrayList<>();
        final Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
