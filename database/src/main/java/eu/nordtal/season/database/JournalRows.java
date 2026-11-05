package eu.nordtal.season.database;

import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Update;
import org.jspecify.annotations.Nullable;

/** Writes the row of a journal family for a {@link JournalDetail}. */
final class JournalRows {

    private JournalRows() {}

    /** Inserts one row into the table of a family from the column names and values in {@code pairs}. */
    static void row(final Handle handle, final String table, final UUID entry, final @Nullable Object... pairs) {
        final StringJoiner columns = new StringJoiner(", ", "(entry, ", ")");
        final StringJoiner values = new StringJoiner(", ", "(:entry, ", ")");
        for (int at = 0; at < pairs.length; at += 2) {
            columns.add((String) pairs[at]);
            values.add(":" + pairs[at]);
        }
        final Update insert = handle.createUpdate("INSERT INTO " + table + " " + columns + " VALUES " + values)
                .bind("entry", Objects.requireNonNull(entry, "entry"));
        for (int at = 0; at < pairs.length; at += 2) {
            insert.bind((String) pairs[at], pairs[at + 1]);
        }
        insert.execute();
    }
}
