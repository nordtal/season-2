package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.value.Kind;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Declares what one message offers its texts. {@link MessageCheck} takes it as its input.
 *
 * @param values every placeholder a text may name, its own and the globals', with its kind
 * @param roles the message's own arguments, each a placeholder or a role, which every packaged text
 *     uses
 * @param limit how many characters the strictest place it is shown in takes, {@code 0} for no limit
 * @param examples each placeholder's example, as {@link Kind#example} reads it, for the length
 *     check
 * @param oneLine whether every place it is shown draws one line, where a line break shows as an
 *     unknown character
 */
public record Declaration(
        Map<String, Kind> values,
        Set<String> roles,
        Set<String> actions,
        boolean markup,
        int limit,
        Map<String, String> examples,
        boolean oneLine) {

    public Declaration {
        values = Map.copyOf(values);
        roles = Set.copyOf(roles);
        actions = Set.copyOf(actions);
        examples = Map.copyOf(examples);
    }

    /**
     * Returns a typed value for each placeholder of the message's own arguments, as an editor shows them.
     * The value is the one {@code given}, else the declared example. A placeholder neither of them
     * parses is left out, as is every global.
     */
    public Map<String, Object> example(final Map<String, String> given) {
        final Map<String, Object> args = new TreeMap<>();
        for (final Map.Entry<String, Kind> value : values.entrySet()) {
            final String name = value.getKey();
            final int dot = name.indexOf('.');
            if (!roles.contains(dot < 0 ? name : name.substring(0, dot))) {
                continue;
            }
            final List<String> candidates = new ArrayList<>(2);
            final String typed = given.get(name);
            if (typed != null) {
                candidates.add(typed);
            }
            final String declared = examples.get(name);
            if (declared != null) {
                candidates.add(declared);
            }
            for (final String candidate : candidates) {
                try {
                    args.put(name, value.getValue().example(candidate));
                    break;
                } catch (final IllegalArgumentException | DateTimeException unread) {
                    // Tries the next candidate.
                }
            }
        }
        return args;
    }
}
