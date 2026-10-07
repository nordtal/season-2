package eu.nordtal.season.database.command;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.notify.Channel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;

/** The only implementation of {@link CommandTreeStore}; it borrows the pool it is given and owns nothing. */
final class JdbiCommandTreeStore implements CommandTreeStore {

    private final Jdbi jdbi;

    JdbiCommandTreeStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void publish(final String server, final CommandTree tree) {
        // The tree is bound twice rather than read from excluded, which would need a server to read every column.
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            INSERT INTO command_tree (server, tree, published)
                            VALUES (:server, CAST(:tree AS jsonb), now())
                            ON CONFLICT (server) DO UPDATE SET tree = CAST(:tree AS jsonb), published = now()""")
                    .bind("server", server)
                    .bind("tree", Json.encode(tree))
                    .execute();
            // In the writing transaction, so a listener woken by the signal reads what was written.
            handle.createQuery("SELECT pg_notify(:channel, :server) IS NULL")
                    .bind("channel", Channel.COMMAND_TREE.sqlName())
                    .bind("server", server)
                    .mapTo(Boolean.class)
                    .one();
        });
    }

    @Override
    public Map<String, Instant> published() {
        final Map<String, Instant> published = new LinkedHashMap<>();
        jdbi.useHandle(handle -> handle.createQuery("SELECT server, published FROM command_tree ORDER BY server")
                .map((rows, context) -> Map.entry(
                        rows.getString("server"), rows.getTimestamp("published").toInstant()))
                .forEach(row -> published.put(row.getKey(), row.getValue())));
        return published;
    }

    @Override
    public Optional<CommandTree> tree(final String server) {
        return jdbi.withHandle(
                handle -> handle.createQuery("SELECT tree::text FROM command_tree WHERE server = :server")
                        .bind("server", server)
                        .mapTo(String.class)
                        .findOne()
                        .map(tree -> Json.decode(tree, CommandTree.class)));
    }
}
