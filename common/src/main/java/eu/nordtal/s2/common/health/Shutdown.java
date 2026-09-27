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
     * Loads this class while the jar it comes from still exists; call it from {@code onEnable}.
     * Otherwise its first load happens in {@code onDisable}, after a replaced jar is gone.
     */
    public static void warmUp() {
        // Empty on purpose. The call is the point: it loads, links and initialises this class.
    }

    /**
     * Runs one step and logs anything it throws instead of propagating it.
     *
     * @param what what the step is, for the one line a failure produces, such as {@code "duels.stop"}
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
