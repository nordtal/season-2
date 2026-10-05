package eu.nordtal.s2.discordbot;

import eu.nordtal.s2.database.alert.Alert;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Raises an alert once per key until that key is cleared, so a failure every sweep repeats is told once.
 *
 * Held in memory: a restart tells a failure that is still there once more.
 */
public final class AlertOnce {

    private final Consumer<Alert> alerts;
    private final Set<String> raised = ConcurrentHashMap.newKeySet();

    public AlertOnce(final Consumer<Alert> alerts) {
        this.alerts = alerts;
    }

    /** Raises {@code alert} unless one of {@code key} is raised and not cleared, and returns whether it did. */
    public boolean raise(final String key, final Alert alert) {
        if (!raised.add(key)) {
            return false;
        }
        alerts.accept(alert);
        return true;
    }

    /** Lets the next failure of {@code key} be told again, because this one is over. */
    public void clear(final String key) {
        raised.remove(key);
    }
}
