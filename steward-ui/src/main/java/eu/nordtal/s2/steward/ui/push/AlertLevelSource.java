package eu.nordtal.s2.steward.ui.push;

/**
 * Where {@link AlertWatch} reads the traffic light's current state from.
 *
 * An interface rather than a direct call to {@code WorkerAlertLevelSource}, so that
 * {@code AlertWatchTest} can hand {@link AlertWatch} a state sequence it chose.
 */
public interface AlertLevelSource {

    /**
     * The current reading.
     *
     * May throw an unchecked exception; {@link AlertWatch} treats that as nothing to report this
     * cycle and tries again on the next one.
     */
    AlertReading current();
}
