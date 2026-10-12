package eu.nordtal.season.database.alert;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One fact an alert measured, as typed values beside the messages that tell it, to filter and compare alerts by.
 *
 * @param gauge what was measured, which also says which of the other fields it fills
 * @param subject what it was measured on: a service, a backup series, {@code disk} or {@code memory}
 * @param value the number read, in the gauge's unit; null for a gauge that reads a word
 * @param limit the threshold the number was held against; null where there is none
 * @param state the word read, such as Docker's state of a container; null for a gauge that reads a number
 */
public record Measure(
        Gauge gauge,
        String subject,
        @Nullable Double value,
        @Nullable Double limit,
        @Nullable String state) {

    /** What an alert can measure. */
    public enum Gauge {
        /** The share of the disk in use, in percent, against its threshold. */
        DISK_PERCENT,

        /** The share of host memory in use, in percent, against its threshold. */
        MEMORY_PERCENT,

        /** The age of a backup series' newest file, in hours, against the permitted age. */
        BACKUP_AGE_HOURS,

        /** The state Docker reports for a container that should run. */
        CONTAINER_STATE,

        /** The health Docker reports for a running container. */
        HEALTH,

        /** The code a container that runs once exited with. */
        EXIT_CODE,

        /** A container runs an older image than the registry has. */
        OLDER_IMAGE
    }

    public Measure {
        Objects.requireNonNull(gauge, "gauge");
        Objects.requireNonNull(subject, "subject");
    }

    /** A number held against a limit. */
    public static Measure against(final Gauge gauge, final String subject, final double value, final double limit) {
        return new Measure(gauge, subject, value, limit, null);
    }

    /** A number with no limit. */
    public static Measure of(final Gauge gauge, final String subject, final double value) {
        return new Measure(gauge, subject, value, null, null);
    }

    /** A word, or for {@code state} null, only that the gauge applies. */
    public static Measure state(final Gauge gauge, final String subject, final @Nullable String state) {
        return new Measure(gauge, subject, null, null, state);
    }
}
