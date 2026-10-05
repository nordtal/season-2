package eu.nordtal.season.database.command;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.Jdbis;
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
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO command_tree (server, tree, published)
                        VALUES (:server, CAST(:tree AS jsonb), now())
                        ON CONFLICT (server) DO UPDATE SET tree = CAST(:tree AS jsonb), published = now()""")
                .bind("server", server)
                .bind("tree", Json.encode(tree))
                .execute());
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
