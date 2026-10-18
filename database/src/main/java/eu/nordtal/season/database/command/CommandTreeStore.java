package eu.nordtal.season.database.command;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Stores each server's command tree. A server writes its own, steward reads it.
 *
 * A write sends {@link eu.nordtal.season.database.notify.Channel#COMMAND_TREE}.
 */
public interface CommandTreeStore {

    /** Returns a store that uses {@code dataSource} and never closes it. */
    static CommandTreeStore using(final DataSource dataSource) {
        return new JdbiCommandTreeStore(dataSource);
    }

    /** Replaces the tree of {@code server}. */
    void publish(String server, CommandTree tree);

    /** Returns the tree of {@code server}, or nothing if it never published. */
    Optional<CommandTree> tree(String server);

    /** Returns the time of each server's last publish, by service. */
    Map<String, Instant> published();
}
