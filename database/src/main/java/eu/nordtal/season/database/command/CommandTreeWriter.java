package eu.nordtal.season.database.command;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Writes the command tree of one server unless it equals the last tree written. */
public final class CommandTreeWriter {

    private final CommandTreeStore store;
    private final String server;
    private @Nullable CommandTree written;

    /** @param server the service name of the server, which keys its row */
    public CommandTreeWriter(final CommandTreeStore store, final String server) {
        this.store = Objects.requireNonNull(store, "store");
        this.server = Objects.requireNonNull(server, "server");
    }

    /**
     * Writes {@code tree} unless it equals the last tree written. A failed write is retried with the next tree.
     *
     * @return whether the tree was written
     */
    public synchronized boolean write(final CommandTree tree) {
        if (tree.equals(written)) {
            return false;
        }
        written = null;
        store.publish(server, tree);
        written = tree;
        return true;
    }
}
