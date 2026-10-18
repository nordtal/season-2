package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** Schedules the nightly {@code BACKUP}. The agent decides what it saves, keeps and copies off the host. */
@ConfigSpec
public interface BackupSpec {

    @Order(7)
    @Name("Time of day")
    @Key("at")
    @Explain("Empty means no nightly backup.")
    default String at() {
        return "04:45";
    }

    @Order(8)
    @Name("Days")
    @Key("days")
    @Explain("An empty list means no nightly backup.")
    default List<String> days() {
        return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    }
}
