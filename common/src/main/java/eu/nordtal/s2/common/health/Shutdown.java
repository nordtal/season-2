package eu.nordtal.s2.common.health;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * One step of a plugin's {@code onDisable}, isolated from the next.
 *
 * <p>Found on the local stack on 2026-09-06: {@code deploy/dev deploy smp} replaced the plugin's
 * jar under the running server, and the restart's {@code onDisable} died on its fourth step with a
 * {@code NoClassDefFoundError} for an inner class the JVM had never needed until then - the old
 * classloader was reading from a file that no longer existed. Everything after that step was
 * skipped: the grave and board displays stayed in the world, the poller and the pool were never
 * closed. {@code updater apply} does exactly the same thing to a production server - it swaps the
 * jar first and restarts second - so this is not a development-only shape.</p>
 *
 * <p>A disable step that throws is logged and the next one runs. The alternative is a shutdown
 * sequence that is only as long as its most fragile line.</p>
 */
public final class Shutdown {

    private Shutdown() {
    }

    /**
     * Loads this class while the jar it comes from still exists. <b>Call it from {@code onEnable}.</b>
     *
     * <p>Without it the guard is the first thing the guard cannot survive. A class is loaded on
     * first active use, and the only use of this one is inside {@code onDisable} - so on the very
     * restart it was written for, the first {@code quietly(...)} call threw
     * {@code ClassNotFoundException: eu.nordtal.s2.common.health.Shutdown} and the whole disable
     * sequence died at step one, which is <em>worse</em> than the failure it fixes. Observed on the
     * local stack the same day it was written, on the first deploy that carried it (finding 115).
     * </p>
     *
     * <p>It warms this class and nothing else, deliberately: a step whose own classes have gone is
     * exactly the case {@link #quietly} exists to absorb, and warming every class an
     * {@code onDisable} could reach is not a thing a plugin can do.</p>
     */
    public static void warmUp() {
        // Empty on purpose. The call is the point: it loads, links and initialises this class.
    }

    /**
     * @param what what the step is, for the one line a failure produces - e.g. {@code "duels.stop"}
     * @param step the step
     * @param warn where the line goes: message and cause
     */
    public static void quietly(final String what, final Runnable step,
                               final BiConsumer<String, Throwable> warn) {
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(warn, "warn");
        try {
            step.run();
        } catch (final RuntimeException | LinkageError failure) {
            // LinkageError is the NoClassDefFoundError of a replaced jar; it is not an Exception
            // and a plain catch would let it through, which is what happened.
            warn.accept("disable step " + what + " failed; carrying on with the next one", failure);
        }
    }
}
