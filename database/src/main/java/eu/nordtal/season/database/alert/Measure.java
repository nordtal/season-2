package eu.nordtal.season.database.alert;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One fact an alert measured, as typed values to filter and compare alerts by.
 *
 * @param gauge what was measured, which decides the fields that are set
 * @param subject what it was measured on: a service, a backup series, {@code disk} or {@code memory}
 * @param value the number read in the gauge's unit, null for a word
 * @param limit the threshold, null if there is none
 * @param state the word read, such as Docker's state of a container, null for a number
 */
public record Measure(
        Gauge gauge,
        String subject,
        @Nullable Double value,
        @Nullable Double limit,
        @Nullable String state) {

    /** What an alert can measure. */
    public enum Gauge {
        /** Percent of the disk in use, against its threshold. */
        DISK_PERCENT,

        /** Percent of host memory in use, against its threshold. */
        MEMORY_PERCENT,

        /** Age in hours of a backup series' newest file, against the permitted age. */
        BACKUP_AGE_HOURS,

        /** The state Docker reports for a container that should run. */
        CONTAINER_STATE,

        /** The health Docker reports for a running container. */
        HEALTH,

        /** The exit code of a container that runs once. */
        EXIT_CODE,

        /** A container runs an older image than the registry has. */
        OLDER_IMAGE
    }

    public Measure {
        Objects.requireNonNull(gauge, "gauge");
        Objects.requireNonNull(subject, "subject");
    }

    /** Creates a number held against a limit. */
    public static Measure against(final Gauge gauge, final String subject, final double value, final double limit) {
        return new Measure(gauge, subject, value, limit, null);
    }

    /** Creates a number without a limit. */
    public static Measure of(final Gauge gauge, final String subject, final double value) {
        return new Measure(gauge, subject, value, null, null);
    }

    /** Creates a word, or with a null {@code state} only the fact that the gauge applies. */
    public static Measure state(final Gauge gauge, final String subject, final @Nullable String state) {
        return new Measure(gauge, subject, null, null, state);
    }
}
