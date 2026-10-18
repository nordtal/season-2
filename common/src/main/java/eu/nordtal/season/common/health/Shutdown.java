package eu.nordtal.season.common.health;

import java.util.Objects;
import java.util.function.BiConsumer;

/** Runs one step of a plugin's {@code onDisable}. A step that throws is logged, and the next step still runs. */
public final class Shutdown {

    private Shutdown() {}

    /**
     * Loads this class while its jar exists.
     * Call it in {@code onEnable}, so that a replaced jar does not break {@code onDisable}.
     */
    public static void warmUp() {
    }

    /** Runs {@code step}, and logs a failure with the text {@code what} instead of throwing it. */
    public static void quietly(final String what, final Runnable step, final BiConsumer<String, Throwable> warn) {
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(warn, "warn");
        try {
            step.run();
        } catch (final RuntimeException | LinkageError failure) {
            // LinkageError is not an Exception.
            warn.accept("disable step " + what + " failed; carrying on with the next one", failure);
        }
    }
}
