package eu.nordtal.season.steward.game;

import eu.nordtal.season.database.command.CommandTree;
import eu.nordtal.season.database.command.CommandTreeStore;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The command tree each server publishes of itself, which the console suggests from. */
public final class ConsoleCommands {

    private final @Nullable CommandTreeStore store;

    public ConsoleCommands(final @Nullable CommandTreeStore store) {
        this.store = store;
    }

    /** One server's commands, node 0 the root; no nodes when the service never published any. */
    public record ConsoleTree(List<CommandTree.Node> nodes) {}

    /** When each server published its tree last, which the live topic compares instead of every tree. */
    public Map<String, Instant> published() {
        return store == null ? Map.of() : store.published();
    }

    /** {@code GET /api/services/{name}/commands}. */
    public void tree(final Context ctx) {
        ctx.json(new ConsoleTree(
                store == null
                        ? List.of()
                        : store.tree(ctx.pathParam("name"))
                                .map(CommandTree::nodes)
                                .orElse(List.of())));
    }
}
