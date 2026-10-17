package eu.nordtal.season.database.audit;

import org.jspecify.annotations.Nullable;

/**
 * What the journal is narrowed to; every part is optional, and a {@code null} or blank one matches anything.
 *
 * @param action    the exact {@link JournalAction}'s name
 * @param subject   the exact Discord id of the person an entry concerns
 * @param reference the exact payment reference the booking of an entry names
 * @param minDays   the least number of days an entry grants or books
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
