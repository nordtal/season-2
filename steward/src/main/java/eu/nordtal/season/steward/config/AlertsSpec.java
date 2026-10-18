package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** Holds the {@code alerts} group: the thresholds of the measured alerts. */
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
    @NoExplanationNeeded
    default int memoryPercent() {
        return 90;
    }

    /** Applies to the age of the files on disk, not to runs that report success. */
    @Order(3)
    @Name("Backup age (hours)")
    @Key("backup-age-hours")
    @NoExplanationNeeded
    default int backupAgeHours() {
        return 36;
    }
}
