package eu.nordtal.season.discordbot;

import eu.nordtal.season.database.alert.Alert;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class AlertOnce {

    private final Consumer<Alert> alerts;
    private final Set<String> raised = ConcurrentHashMap.newKeySet();

    public AlertOnce(final Consumer<Alert> alerts) {
        this.alerts = alerts;
    }

    public boolean raise(final String key, final Alert alert) {
        if (!raised.add(key)) {
            return false;
        }
        alerts.accept(alert);
        return true;
    }

    public void clear(final String key) {
        raised.remove(key);
    }
}
