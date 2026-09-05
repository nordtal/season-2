package eu.nordtal.s2.commands.limbo;

import eu.nordtal.s2.commands.CommandEffects;

/**
 * The one thing {@code /limbo reload} touches, and the reason it is only one.
 *
 * <p>{@code config.yml} names the waiting world and the title refresh interval, and re-reading those
 * while the room is running would mean rebuilding it under the players standing in it. A message is
 * safe to swap mid-flight; a world is not.</p>
 */
public interface LimboEffects extends CommandEffects {

    /**
     * Re-read the message bundles and the operator's override.
     *
     * @return whether they loaded. A failure leaves the running ones untouched, which is the whole
     *         reason this answers rather than throwing: the waiting room keeps working with the
     *         wording it already had
     */
    boolean reloadMessages();
}
