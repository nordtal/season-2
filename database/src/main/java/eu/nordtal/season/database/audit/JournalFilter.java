package eu.nordtal.season.database.audit;

import org.jspecify.annotations.Nullable;

/**
 * What the journal is narrowed to; every part is optional, and a {@code null} or blank one matches anything.
 *
 * @param action    the exact {@code audit_log.action}
 * @param subject   the exact Discord id of the person a line concerns
 * @param reference the exact payment reference a line names
 * @param minDays   the least number of days a line grants or books
 */
public record JournalFilter(
        @Nullable String action,
        @Nullable String subject,
        @Nullable String reference,
        @Nullable Integer minDays) {

    public static final JournalFilter ANY = new JournalFilter(null, null, null, null);

    public static JournalFilter of(final JournalAction action) {
        return new JournalFilter(action.name(), null, null, null);
    }
}
