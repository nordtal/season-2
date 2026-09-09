package eu.nordtal.s2.commands.info;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;

/**
 * Printing a block of standing text at somebody, in their language.
 *
 * <h2>Why the text is not named here and not rendered here either</h2>
 * {@code /discord} and {@code /rules} say something the network has decided once and says the same
 * way for a season. That text wants a clickable link, a colour and more than one line - none of
 * which can live in {@code :commands}' shared bundle, which carries no markup at all because Discord
 * reads the same file. So the <em>key</em> is chosen by the command, which is what makes it
 * assertable, and the <em>value</em> lives in the proxy's own bundle, where MiniMessage is allowed.
 *
 * <p>Which also means these two commands are the one place in this module where a message key names
 * something outside it. {@code InfoCommandsTest} pins which key each command asks for, and
 * {@code network-control}'s own bundle test pins that both exist in both languages - the two halves
 * of a seam that nothing else compares.</p>
 */
public interface InfoEffects extends CommandEffects {

    /**
     * Send whatever {@code messageKey} names, rendered in {@code user}'s language.
     *
     * <p>The implementation substitutes whatever that text needs - today the Discord invite out of
     * {@code gate.yml}, which is the same value the login screens already use, so an invite that
     * has been re-issued is re-issued in one place.</p>
     */
    void show(NordtalUser user, String messageKey);
}
