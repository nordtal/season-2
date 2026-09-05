package eu.nordtal.s2.commands.network;

import eu.nordtal.s2.commands.CommandEffects;

/**
 * The one thing {@code /network reload} touches.
 *
 * <p>{@code gate.yml}, {@code pack.yml}, {@code network.yml} and {@code database.yml} are all read
 * once, at startup, and every one is wired into something that cannot be swapped under a running
 * proxy: the login gate's backend names, the pack's URL and hash, the connection pool. A command
 * that re-read them would either do nothing or do something worse than nothing. The MOTD and every
 * disconnect screen are strings, and those are exactly what somebody wants to fix at eight in the
 * evening without dropping everyone who is online.</p>
 */
public interface NetworkEffects extends CommandEffects {

    /** Re-read the message bundles and the operator's override. */
    boolean reloadMessages();
}
