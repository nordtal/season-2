package eu.nordtal.season.steward.access;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.journal.JournalItem;
import eu.nordtal.season.database.journal.JournalRun;
import eu.nordtal.season.database.journal.JournalSearch.Term;
import eu.nordtal.season.database.journal.JournalStream;
import eu.nordtal.season.steward.alert.AlertRoutes.CurrentAlert;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Holds the body of {@code GET /api/journal}: a page of the stream and how each word of the query was read.
 *
 * @param next the cursor of the page after it, null on the last
 */
public record JournalPage(List<Item> items, @Nullable String next, List<Term> terms) {

    static JournalPage of(final JournalStream.Page page, final List<Term> terms) {
        return new JournalPage(page.items().stream().map(Item::of).toList(), page.next(), terms);
    }

    /**
     * One row: an entry, an alert or a run, exactly one of the three set.
     *
     * @param opened when the problem an alert raises or clears began, null for an alert that is no measurement
     */
    public record Item(
            String key,
            Instant occurred,
            Actor actor,
            @Nullable AuditEntry entry,
            @Nullable CurrentAlert alert,
            @Nullable Instant opened,
            @Nullable JournalRun run) {

        static Item of(final JournalItem item) {
            return new Item(
                    item.key(),
                    item.occurred(),
                    item.actor(),
                    item.entry(),
                    item.alert() == null ? null : CurrentAlert.of(item.alert().alert()),
                    item.opened(),
                    item.run());
        }
    }
}
