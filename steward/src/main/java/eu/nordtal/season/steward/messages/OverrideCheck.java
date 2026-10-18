package eu.nordtal.season.steward.messages;

import eu.nordtal.season.internalapi.agent.MessageArg;
import eu.nordtal.season.internalapi.agent.MessageEntry;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.value.Kind;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Checks a text of an admin's save with the shared validator against the schema of the jar. */
public final class OverrideCheck {

    private OverrideCheck() {}

    /** Returns the problems of {@code text} for {@code entry}, and none for a key without a schema. */
    static List<MessageCheck.Problem> problems(final MessageEntry entry, final @Nullable String text) {
        if (text == null || !entry.described()) {
            return List.of();
        }
        return MessageCheck.check(text, declaration(entry), MessageCheck.Mode.OVERRIDE);
    }

    /** Returns the declaration of a described entry from the schema of its jar. */
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
        final List<Display> shown = entry.shown().stream()
                .flatMap(name -> Arrays.stream(Display.values())
                        .filter(place -> place.name().equals(name)))
                .toList();
        return new Declaration(
                values,
                roles,
                actions,
                "MINIMESSAGE".equals(entry.format()),
                entry.limit(),
                examples,
                Display.oneLine(shown));
    }
}
