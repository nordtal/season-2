package eu.nordtal.s2.commands.limbo;

import eu.nordtal.s2.commands.CommandEffects;

/** The one thing {@code /limbo reload} touches: a message is safe to swap mid-flight, a world is not. */
public interface LimboEffects extends CommandEffects {

    /**
     * Re-reads the message bundles and the operator's override.
     *
     * @return whether they loaded; a failure keeps the wording already running
     */
    boolean reloadMessages();
}
