package eu.nordtal.season.proxy.command;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.command.CommandTree;
import eu.nordtal.season.database.command.CommandTreeWriter;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Publishes the proxy's whole command tree for Steward's console: Velocity's own, every plugin's and ours.
 *
 * The tree is read as the console may use it, a while after start and after every reload, and written when it changed.
 */
public final class CommandTrees {

    /** How long after the initialize event every other plugin has registered its commands. */
    static final Duration SETTLE = Duration.ofSeconds(10);

    /** Brigadier's nodes as the walk reads them. */
    static final CommandTree.Shape<CommandNode<?>> BRIGADIER = new CommandTree.Shape<>() {
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

    private final CommandManager commands;
    private final CommandSource console;
    private final CommandTreeWriter writer;
    private final Scheduler scheduler;
    private final Logger logger;

    public CommandTrees(
            final CommandManager commands,
            final CommandSource console,
            final CommandTreeWriter writer,
            final Scheduler scheduler,
            final Logger logger) {
        this.commands = commands;
        this.console = console;
        this.writer = writer;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /** Reads the tree once the other plugins have had their time to register, and writes it when it changed. */
    public void readSoon() {
        final var _ = scheduler.after(SETTLE, this::read);
    }

    /** A reload may have changed what a plugin registers. */
    @Subscribe
    public void reloaded(final ProxyReloadEvent event) {
        readSoon();
    }

    /**
     * Copies every command the console may use into {@code into}, as Velocity copies them for a player's client.
     *
     * The API hands out no tree, so this calls the injector Velocity's own command manager exposes.
     * @throws IllegalStateException when the command manager has no injector of that shape
     */
    static <S> void copy(final Object commands, final RootCommandNode<S> into, final S source) {
        try {
            final Object injector = commands.getClass().getMethod("getInjector").invoke(commands);
            injector.getClass()
                    .getMethod("inject", RootCommandNode.class, Object.class)
                    .invoke(injector, into, source);
        } catch (final InvocationTargetException failed) {
            throw new IllegalStateException("the command injector failed", failed.getCause());
        } catch (final ReflectiveOperationException | RuntimeException refused) {
            throw new IllegalStateException(
                    commands.getClass().getName() + " hands out no command tree: " + refused, refused);
        }
    }

    private void read() {
        final CommandTree tree;
        try {
            final RootCommandNode<CommandSource> root = new RootCommandNode<>();
            copy(commands, root, console);
            tree = CommandTree.of(root, BRIGADIER);
        } catch (final IllegalStateException unreadable) {
            logger.warn("The command tree cannot be read: {}", unreadable.getMessage());
            return;
        }
        try {
            if (writer.write(tree)) {
                logger.info("Published the command tree, {} nodes", tree.nodes().size());
            }
        } catch (final RuntimeException failed) {
            logger.warn("The command tree could not be published: {}", failed.getMessage());
        }
    }
}
