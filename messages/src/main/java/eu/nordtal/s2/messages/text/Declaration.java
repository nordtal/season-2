package eu.nordtal.s2.messages.text;

import eu.nordtal.s2.messages.value.Kind;
import java.util.Map;
import java.util.Set;

/**
 * What one message offers its texts, as its spec declares it and its schema carries it.
 * It is the one input of {@link MessageCheck}, at build time, on an admin's save and when a process loads overrides.
 *
 * @param values   every placeholder a text may name, its own and the globals', to its kind
 * @param untyped  placeholders declared without a kind, which any kind may fill; only a bundle not yet typed has them
 * @param roles    the message's own arguments, each a placeholder or a role, which every packaged text uses
 * @param actions  the actions a text may place with {@code <action:name>}
 * @param markup   whether the texts are MiniMessage
 * @param limit    how many characters the place it is shown in takes, {@code 0} for no limit
 * @param examples placeholder to its example, as {@link Kind#example} reads it, for the length check
 */
public record Declaration(
        Map<String, Kind> values,
        Set<String> untyped,
        Set<String> roles,
        Set<String> actions,
        boolean markup,
        int limit,
        Map<String, String> examples) {

    public Declaration {
        values = Map.copyOf(values);
        untyped = Set.copyOf(untyped);
        roles = Set.copyOf(roles);
        actions = Set.copyOf(actions);
        examples = Map.copyOf(examples);
    }
}
