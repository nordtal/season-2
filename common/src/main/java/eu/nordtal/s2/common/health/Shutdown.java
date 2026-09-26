package eu.nordtal.s2.common.health;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Runs one step of a plugin's {@code onDisable}, isolated from the next.
 *
 * A step that throws is logged and the next one runs, so a replaced jar cannot skip the rest.
 */
public final class Shutdown {

    private Shutdown() {}

    /**
     * Loads this class while the jar it comes from still exists. <b>Call it from {@code onEnable}.</b>
     *
     * Without it the guard is the first thing the guard cannot survive. A class is loaded on
     * first active use, and the only use of this one is inside {@code onDisable} - so on the very
     * restart it was written for, the first {@code quietly(...)} call threw
     * {@code ClassNotFoundException: eu.nordtal.s2.common.health.Shutdown} and the whole disable
     * sequence died at step one, which is <em>worse</em> than the failure it fixes. Observed on the
     * local stack the same day it was written, on the first deploy that carried it (finding 115).
     *
     * It warms this class and nothing else, deliberately: a step whose own classes have gone is
     * exactly the case {@link #quietly} exists to absorb, and warming every class an
     * {@code onDisable} could reach is not a thing a plugin can do.
     */
    public static void warmUp() {
        // Empty on purpose. The call is the point: it loads, links and initialises this class.
    }

    /**
     * @param what what the step is, for the one line a failure produces - e.g. {@code "duels.stop"}
     * @param step the step
     * @param warn where the line goes: message and cause
     */
    public static void quietly(final String what, final Runnable step, final BiConsumer<String, Throwable> warn) {
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(warn, "warn");
        try {
            step.run();
        } catch (final RuntimeException | LinkageError failure) {
            // LinkageError, such as a replaced jar's NoClassDefFoundError, is not an Exception.
            warn.accept("disable step " + what + " failed; carrying on with the next one", failure);
        }
    }
}
