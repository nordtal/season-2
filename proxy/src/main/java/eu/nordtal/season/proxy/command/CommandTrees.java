package eu.nordtal.season.proxy.command;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.command.CommandTree;
import eu.nordtal.season.database.command.CommandTreeWriter;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Publishes the proxy's whole command tree for Steward's console: Velocity's own, every plugin's and ours.
 *
 * Velocity announces no registration, so the tree is read every {@link #EVERY} and written when it changed.
 */
public final class CommandTrees {

    /** How often the tree is read, the first time once every other plugin has had that long to register. */
    static final Duration EVERY = Duration.ofSeconds(10);

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

    private final Supplier<CommandTree> reader;
    private final CommandTreeWriter writer;
    private final Scheduler scheduler;
    private final Logger logger;
    private @Nullable String problem;

    CommandTrees(
            final Supplier<CommandTree> reader,
            final CommandTreeWriter writer,
            final Scheduler scheduler,
            final Logger logger) {
        this.reader = reader;
        this.writer = writer;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /** Publishes the tree {@code console} may use, as Velocity's command manager holds it. */
    public static CommandTrees of(
            final CommandManager commands,
            final CommandSource console,
            final CommandTreeWriter writer,
            final Scheduler scheduler,
            final Logger logger) {
        return new CommandTrees(
                () -> {
                    final RootCommandNode<CommandSource> root = new RootCommandNode<>();
                    copy(commands, root, console);
                    return CommandTree.of(root, BRIGADIER);
                },
                writer,
                scheduler,
                logger);
    }

    /** Reads the tree every {@link #EVERY} from now on, and writes it whenever it changed. */
    public void start() {
        final var _ = scheduler.every(EVERY, EVERY, this::read);
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
        try {
            final CommandTree tree = reader.get();
            if (writer.write(tree)) {
                logger.info("Published the command tree, {} nodes", tree.nodes().size());
            }
            problem = null;
        } catch (final RuntimeException failed) {
            // Read again every few seconds, so a lasting failure is said once rather than on every read.
            final String said = "The command tree could not be published: " + failed.getMessage();
            if (!said.equals(problem)) {
                logger.warn(said);
            }
            problem = said;
        }
    }
}
