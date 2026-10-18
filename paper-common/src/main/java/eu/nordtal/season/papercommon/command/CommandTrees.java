package eu.nordtal.season.papercommon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import eu.nordtal.season.database.command.CommandTree;
import eu.nordtal.season.database.command.CommandTreeWriter;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import io.papermc.paper.command.brigadier.Commands;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Publishes this server's command tree for Steward's console.
 * It reads on the main thread and writes off it when the tree changed.
 */
public final class CommandTrees implements Listener {

    public static final CommandTree.Shape<CommandNode<?>> BRIGADIER = new CommandTree.Shape<>() {
        @Override
        public String name(final CommandNode<?> node) {
            return node.getName();
        }

        @Override
        public boolean argument(final CommandNode<?> node) {
            return node instanceof ArgumentCommandNode<?, ?>;
        }

        @Override
        public boolean executes(final CommandNode<?> node) {
            return node.getCommand() != null;
        }

        @Override
        public Collection<? extends CommandNode<?>> children(final CommandNode<?> node) {
            return node.getChildren();
        }

        @Override
        public @Nullable CommandNode<?> redirect(final CommandNode<?> node) {
            return node.getRedirect();
        }
    };

    private final Plugin plugin;
    private final CommandTreeWriter writer;
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile @Nullable CommandDispatcher<?> dispatcher;

    public CommandTrees(final Plugin plugin, final CommandTreeWriter writer) {
        this.plugin = plugin;
        this.writer = writer;
    }

    /** Takes the dispatcher from the commands event at the start and on every reload, and reads it a tick later. */
    public void take(final Commands registrar) {
        try {
            dispatcher = game(registrar.getDispatcher());
        } catch (final IllegalStateException unreadable) {
            plugin.getLogger().warning("The command tree cannot be read: " + unreadable.getMessage());
            return;
        }
        readSoon();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void enabled(final PluginEnableEvent event) {
        readSoon();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void disabled(final PluginDisableEvent event) {
        readSoon();
    }

    /**
     * Returns the game's dispatcher behind the one Paper gives plugins, whose nodes cannot be walked.
     * Throws if the command root names none.
     */
    static CommandDispatcher<?> game(final CommandDispatcher<?> mirror) {
        final Object root = mirror.getRoot();
        for (Class<?> type = root.getClass(); type != null; type = type.getSuperclass()) {
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            try {
                final Method named = type.getMethod("getDispatcher");
                if (named.invoke(root) instanceof final CommandDispatcher<?> game) {
                    return game;
                }
            } catch (final NoSuchMethodException notHere) {
                // A public class further up may still declare it.
            } catch (final ReflectiveOperationException refused) {
                throw new IllegalStateException(root.getClass().getName() + " refused its dispatcher", refused);
            }
        }
        throw new IllegalStateException(root.getClass().getName() + " names no dispatcher");
    }

    private void readSoon() {
        if (plugin.getServer().isStopping() || !pending.compareAndSet(false, true)) {
            return;
        }
        try {
            PaperScheduler.of(plugin).onMain(this::read);
        } catch (final RejectedExecutionException disabled) {
            pending.set(false);
        }
    }

    private void read() {
        pending.set(false);
        final CommandDispatcher<?> game = dispatcher;
        if (game == null) {
            return;
        }
        final CommandTree tree = CommandTree.of(game.getRoot(), BRIGADIER);
        PaperScheduler.of(plugin).execute(() -> {
            try {
                if (writer.write(tree)) {
                    plugin.getLogger()
                            .info("Published the command tree, " + tree.nodes().size() + " nodes");
                }
            } catch (final RuntimeException failed) {
                plugin.getLogger().warning("The command tree could not be published: " + failed.getMessage());
            }
        });
    }
}
