package eu.nordtal.season.database.command;

import java.util.Optional;
import javax.sql.DataSource;

/**
 * Each server's command tree: the server writes its own, steward reads it for the console's suggestions.
 *
 * Nothing listens for a change, so a write signals nothing; the console reads the tree when it opens.
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
}
