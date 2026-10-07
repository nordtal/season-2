package eu.nordtal.season.database.command;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Each server's command tree: the server writes its own, steward reads it for the console's suggestions.
 *
 * A write rings {@link eu.nordtal.season.database.notify.Channel#COMMAND_TREE}, so an open console reads it again.
 */
public interface CommandTreeStore {

    /** Returns a store over {@code dataSource}, which it borrows and never closes. */
    static CommandTreeStore using(final DataSource dataSource) {
        return new JdbiCommandTreeStore(dataSource);
    }

    /** Replaces the tree {@code server} published before. */
    void publish(String server, CommandTree tree);

    /** The tree {@code server} published last, or nothing when it never has. */
    Optional<CommandTree> tree(String server);

    /** When each server that ever published published last, by its service. */
    Map<String, Instant> published();
}
