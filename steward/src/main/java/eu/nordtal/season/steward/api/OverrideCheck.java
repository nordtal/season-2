package eu.nordtal.season.steward.api;

import eu.nordtal.season.internalapi.agent.MessageArg;
import eu.nordtal.season.internalapi.agent.MessageEntry;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.value.Kind;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The check of an admin's save: the one validator the build and every process run, over the jar's schema.
 * An error refuses the save; a warning, such as a value the text no longer shows, is answered with it.
 */
final class OverrideCheck {

    private OverrideCheck() {}

    /** Returns what {@code text} gets wrong for {@code entry}; a key the schema does not describe is never checked. */
    static List<MessageCheck.Problem> problems(final MessageEntry entry, final @Nullable String text) {
        if (text == null || !entry.described()) {
            return List.of();
        }
        return MessageCheck.check(text, declaration(entry), MessageCheck.Mode.OVERRIDE);
    }

    /** Returns what a described entry offers its texts, as its jar's schema declared it. */
    static Declaration declaration(final MessageEntry entry) {
        final Map<String, Kind> values = new HashMap<>();
        final Map<String, String> examples = new HashMap<>();
        final Set<String> roles = new HashSet<>();
        final Set<String> actions = new HashSet<>();
        for (final MessageArg arg : entry.args()) {
            if (arg.action()) {
                actions.add(arg.name());
                continue;
            }
            final String kind = arg.kind();
            if (kind != null) {
                values.put(arg.name(), Kind.byToken(kind).orElse(Kind.TEXT));
            }
            if (arg.example() != null) {
                examples.put(arg.name(), arg.example());
            }
            if (!arg.global()) {
                roles.add(
                        arg.type() == null
                                ? arg.name()
                                : arg.name().substring(0, arg.name().indexOf('.')));
            }
        }
        final String shown = entry.shown();
        return new Declaration(
                values,
                roles,
                actions,
                "MINIMESSAGE".equals(entry.format()),
                shown == null ? 0 : Display.valueOf(shown).limit(),
                examples);
    }
}
