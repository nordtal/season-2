package eu.nordtal.s2.database.alert;

import com.google.gson.reflect.TypeToken;
import eu.nordtal.s2.database.DatabaseJson;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.messages.MessageRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** The only implementation of {@link AlertBook}; it borrows the pool it is given and owns nothing. */
final class JdbiAlertBook implements AlertBook {

    private static final TypeToken<List<MessageRef>> LINES = new TypeToken<>() {};

    private static final String COLUMNS = "id, raised, raised_by, type, level, subject, title, detail, path";

    private final Jdbi jdbi;

    JdbiAlertBook(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void raise(final Alert alert, final String raisedBy) {
        jdbi.useTransaction(handle -> insert(handle, alert, raisedBy, null));
    }

    @Override
    public boolean raiseOnce(final String source, final Alert alert, final String raisedBy) {
        Objects.requireNonNull(source, "source");
        return jdbi.inTransaction(handle -> insert(handle, alert, raisedBy, source));
    }

    /** Writes the row and, in the same transaction, the signal, which Postgres sends only on commit. */
    private static boolean insert(
            final Handle handle, final Alert alert, final String raisedBy, final @Nullable String source) {
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(raisedBy, "raisedBy");
        // Only a raise with a source names the conflict, which would ask the bot's role for more than INSERT.
        final int written = handle.createUpdate("""
                        INSERT INTO admin_alert (raised_by, type, level, subject, title, detail, path, source)
                        VALUES (:raisedBy, :type, :level, :subject, cast(:title AS jsonb), cast(:detail AS jsonb), :path,
                                :source)""" + (source == null ? "" : " ON CONFLICT (source) DO NOTHING"))
                .bind("raisedBy", raisedBy)
                .bind("type", alert.type().name())
                .bind("level", alert.level().name())
                .bind("subject", alert.subject())
                .bind("title", DatabaseJson.encode(alert.title()))
                .bind("detail", DatabaseJson.encode(alert.detail()))
                .bind("path", alert.path())
                .bind("source", source)
                .execute();
        if (written > 0) {
            handle.createQuery("SELECT pg_notify('nordtal_alert', '') IS NULL")
                    .mapTo(Boolean.class)
                    .one();
        }
        return written > 0;
    }

    @Override
    public List<RaisedAlert> claimUnrouted() {
        return jdbi.withHandle(handle -> handle
                .createQuery("UPDATE admin_alert SET routed = now() WHERE routed IS NULL" + " RETURNING " + COLUMNS)
                .map((rows, context) -> raisedOf(rows))
                .list()
                .stream()
                .sorted(Comparator.comparingLong(RaisedAlert::id))
                .toList());
    }

    @Override
    public List<RaisedAlert> recent(final int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT " + COLUMNS + " FROM admin_alert ORDER BY raised DESC, id DESC LIMIT :limit")
                .bind("limit", Math.max(0, limit))
                .map((rows, context) -> raisedOf(rows))
                .list());
    }

    @Override
    public int purge(final Duration age) {
        Objects.requireNonNull(age, "age");
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM admin_alert WHERE routed IS NOT NULL"
                        + " AND raised < now() - make_interval(secs => :seconds)")
                .bind("seconds", (double) age.toSeconds())
                .execute());
    }

    private static RaisedAlert raisedOf(final ResultSet rows) throws SQLException {
        return new RaisedAlert(
                rows.getLong("id"),
                rows.getTimestamp("raised").toInstant(),
                rows.getString("raised_by"),
                new Alert(
                        AlertType.valueOf(rows.getString("type")),
                        Alert.Level.valueOf(rows.getString("level")),
                        rows.getString("subject"),
                        DatabaseJson.decode(rows.getString("title"), MessageRef.class),
                        DatabaseJson.decode(rows.getString("detail"), LINES),
                        rows.getString("path")));
    }
}
