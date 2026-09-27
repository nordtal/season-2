package eu.nordtal.s2.commands.network;

import eu.nordtal.s2.commands.CommandEffects;

/**
 * The one thing {@code /network reload} touches: the MOTD and the disconnect screens.
 *
 * Every config file is wired into something that cannot be swapped under a running proxy.
 */
public interface NetworkEffects extends CommandEffects {

    /** Re-reads the message bundles and the operator's override. */
    boolean reloadMessages();
}
