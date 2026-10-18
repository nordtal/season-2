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

/** Publishes the command tree of the proxy for the console of Steward. It reads the tree every {@link #EVERY}. */
public final class CommandTrees {

    /** The interval between two reads. */
    static final Duration EVERY = Duration.ofSeconds(10);

    /** Reads a Brigadier node. */
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

    /** Creates a publisher for the commands that the console may use. */
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

    /** Reads the tree every {@link #EVERY} and publishes it when it changed. */
    public void start() {
        final var _ = scheduler.every(EVERY, EVERY, this::read);
    }

    /**
     * Copies the commands that the source may use into {@code into}. It calls the injector of the command manager.
     *
     * @throws IllegalStateException when the command manager has no injector
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
            final String said = "The command tree could not be published: " + failed.getMessage();
            if (!said.equals(problem)) {
                logger.warn(said);
            }
            problem = said;
        }
    }
}
