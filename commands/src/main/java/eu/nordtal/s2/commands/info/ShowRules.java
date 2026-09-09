package eu.nordtal.s2.commands.info;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;

/**
 * {@code /rules}: what the season expects of people.
 *
 * <h2>The text does not exist yet, and that is visible rather than hidden</h2>
 * Writing the rules is the owner's (todo.md A10). Until they are written, the key this command names
 * resolves to text that says in as many words that it is a placeholder and not a rule - because the
 * one way this ships wrong is quietly: a {@code /rules} that answers with something plausible is a
 * {@code /rules} nobody checks again. Replacing it is an edit to the proxy's message bundle and
 * needs no release.
 */
public final class ShowRules implements NordtalCommand<InfoEffects> {

    @Override
    public Declaration declaration() {
        return InfoCommands.RULES;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final InfoEffects effects) {
        effects.async(() -> effects.show(user, InfoCommands.RULES_TEXT));
    }
}
