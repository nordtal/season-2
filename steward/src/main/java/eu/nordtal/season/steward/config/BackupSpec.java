package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** When the nightly {@code BACKUP} is asked for; what it saves, keeps and copies off the host is the agent's. */
@ConfigSpec
public interface BackupSpec {

    @Order(7)
    @Name("Time of day")
    @Key("at")
    @Explain("Empty means no nightly backup.")
    default String at() {
        return "04:45";
    }

    /** All seven weekdays by default. */
    @Order(8)
    @Name("Days")
    @Key("days")
    @Explain("An empty list means no nightly backup.")
    default List<String> days() {
        return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    }
}
