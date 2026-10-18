package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.value.Kind;
import java.util.Map;
import java.util.Set;

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
}
