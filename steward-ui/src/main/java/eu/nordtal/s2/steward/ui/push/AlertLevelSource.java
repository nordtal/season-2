package eu.nordtal.s2.steward.ui.push;

import org.jetbrains.annotations.NotNull;

/**
 * Where {@link AlertWatch} reads the traffic light's current state from.
 *
 * <p>An interface rather than a direct call to {@code WorkerAlertLevelSource}, so that
 * {@code AlertWatchTest} can hand {@link AlertWatch} a state sequence it chose, without a real
 * steward-worker to poll or a JVM clock to wait on for the light to change.</p>
 */
public interface AlertLevelSource {

    /**
     * The current reading. May throw an unchecked exception (a network failure, an unreachable
     * worker) - {@link AlertWatch} treats that as "nothing to report this cycle", the same as any
     * other transient failure of a background poll, and tries again on the next one.
     */
    @NotNull
    AlertReading current();
}
