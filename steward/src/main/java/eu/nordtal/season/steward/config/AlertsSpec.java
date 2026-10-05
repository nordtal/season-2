package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/**
 * The {@code alerts} group: when steward's measured alerts turn yellow or red, the only copy there is.
 *
 * A group of its own because it applies while steward runs, which the rest of the web group cannot.
 */
@ConfigSpec
public interface AlertsSpec {

    @Order(1)
    @Name("Disk usage (percent)")
    @Key("disk-percent")
    @NoExplanationNeeded
    default int diskPercent() {
        return 85;
    }

    @Order(2)
    @Name("Memory usage (percent)")
    @Key("memory-percent")
    @Explain("A share of the whole host's memory, since no container sets a limit of its own.")
    default int memoryPercent() {
        return 90;
    }

    @Order(3)
    @Name("Backup age (hours)")
    @Key("backup-age-hours")
    @Explain(
            "Counts files actually on disk, not runs that reported success, since a run once reported success having saved nothing.")
    default int backupAgeHours() {
        return 36;
    }
}
