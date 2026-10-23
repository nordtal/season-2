package eu.nordtal.season.steward.game;

import eu.nordtal.season.database.command.CommandTree;
import eu.nordtal.season.database.command.CommandTreeStore;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Serves the command tree that each server publishes. */
public final class ConsoleCommands {

    private final CommandTreeStore store;

    public ConsoleCommands(final CommandTreeStore store) {
        this.store = store;
    }

    /** The commands of one server with node 0 as the root, and no nodes when it published none. */
    public record ConsoleTree(List<CommandTree.Node> nodes) {}

    public Map<String, Instant> published() {
        return store.published();
    }

    public void tree(final Context ctx) {
        ctx.json(new ConsoleTree(
                store.tree(ctx.pathParam("name")).map(CommandTree::nodes).orElse(List.of())));
    }
}
