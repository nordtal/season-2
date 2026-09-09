package eu.nordtal.s2.commands.info;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;

/**
 * {@code /discord}: where the guild is.
 *
 * <p>It is on the proxy rather than on each backend for the reason every command here is: it has to
 * work in the waiting room, where a player who cannot get in is standing and where being told how to
 * reach us is the entire point. A per-server copy would be three copies of one invite.</p>
 */
public final class ShowDiscord implements NordtalCommand<InfoEffects> {

    @Override
    public Declaration declaration() {
        return InfoCommands.DISCORD;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final InfoEffects effects) {
        effects.async(() -> effects.show(user, InfoCommands.DISCORD_TEXT));
    }
}
