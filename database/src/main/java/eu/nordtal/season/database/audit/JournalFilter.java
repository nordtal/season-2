package eu.nordtal.season.database.audit;

import org.jspecify.annotations.Nullable;

/**
 * Narrows the journal. A null or blank part matches anything.
 *
 * @param action the exact name of a {@link JournalAction}
 * @param subject the exact Discord id of the person an entry concerns
 * @param reference the exact payment reference of the booking an entry names
 * @param minDays the least days an entry grants or books
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
