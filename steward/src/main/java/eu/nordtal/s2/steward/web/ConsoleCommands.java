package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.database.command.CommandTree;
import eu.nordtal.s2.database.command.CommandTreeStore;
import io.javalin.http.Context;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The command tree each server publishes of itself, which the console suggests from. */
final class ConsoleCommands {

    private final @Nullable CommandTreeStore store;

    ConsoleCommands(final @Nullable CommandTreeStore store) {
        this.store = store;
    }

    /** One server's commands, node 0 the root; no nodes when the service never published any. */
    record ConsoleTree(List<CommandTree.Node> nodes) {}

    /** {@code GET /api/services/{name}/commands}. */
    void tree(final Context ctx) {
        ctx.json(new ConsoleTree(
                store == null
                        ? List.of()
                        : store.tree(ctx.pathParam("name"))
                                .map(CommandTree::nodes)
                                .orElse(List.of())));
    }
}
