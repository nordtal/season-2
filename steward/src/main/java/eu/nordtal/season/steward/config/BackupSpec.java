package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.Comment;
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
    @Comment({
        "Local time of day the nightly backup is asked for, HH:mm, in this container's TZ.",
        "Empty means none. The zone and the next firing are logged on every start."
    })
    @Explain("Empty means no nightly backup at all, and nothing else in the stack makes one.")
    default String at() {
        return "04:45";
    }

    @Order(8)
    @Name("Days")
    @Key("days")
    @Comment({
        "Which weekdays the nightly backup runs on: full names or three-letter forms, any case.",
        "A word that is not a weekday is logged and ignored. An empty list means no nightly",
        "backup, and retention counts days, so gaps stretch the daily window."
    })
    @Explain(
            "Which weekdays the nightly backup runs on. All seven by default. An empty list means no nightly backup at all.")
    default List<String> days() {
        return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    }
}
