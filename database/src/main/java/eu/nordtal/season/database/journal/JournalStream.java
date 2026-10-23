package eu.nordtal.season.database.journal;

import eu.nordtal.season.database.journal.JournalSearch.Term;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Reads the journal as one stream, newest first: entries, alerts and runs.
 * A run stands for the entries that asked for it and that raised its failure, which the stream leaves out.
 */
public interface JournalStream {

    /** Returns a stream that uses {@code dataSource}. */
    static JournalStream using(final DataSource dataSource) {
        return new JdbiJournalStream(dataSource);
    }

    /** Returns the services named by an entry, a measurement or a run, which a search word can name. */
    Set<String> services();

    /**
     * Returns the rows that match every term, newest first.
     *
     * @param after the {@link Page#next()} of the page before, null for the newest
     * @param limit the maximum count, raised to 1 if lower
     */
    Page page(List<Term> terms, @Nullable String after, int limit);

    /**
     * One page of the stream.
     *
     * @param next the cursor of the page after it, null on the last
     */
    record Page(List<JournalItem> items, @Nullable String next) {

        public Page {
            items = List.copyOf(items);
        }
    }
}
