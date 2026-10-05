package eu.nordtal.season.database.command;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Writes one server's command tree, and skips a tree equal to the last one it wrote. */
public final class CommandTreeWriter {

    private final CommandTreeStore store;
    private final String server;
    private @Nullable CommandTree written;

    /** @param server the service the server runs as, which names its row */
    public CommandTreeWriter(final CommandTreeStore store, final String server) {
        this.store = Objects.requireNonNull(store, "store");
        this.server = Objects.requireNonNull(server, "server");
    }

    /**
     * Writes {@code tree} unless it is the one written last; a failed write is tried again with the next tree.
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
