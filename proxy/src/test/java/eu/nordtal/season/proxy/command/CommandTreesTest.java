package eu.nordtal.season.proxy.command;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.RootCommandNode;
import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.command.CommandTree;
import eu.nordtal.season.database.command.CommandTreeStore;
import eu.nordtal.season.database.command.CommandTreeWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/** The proxy's commands copied as Velocity copies them for a client, and read into a tree. */
class CommandTreesTest {

    /** As Velocity's command manager: a public injector that copies what a source may use into a root. */
    public static final class Manager {
        public Injector getInjector() {
            return new Injector();
        }
    }

    /** As Velocity's CommandGraphInjector. */
    public static final class Injector {
        public void inject(final RootCommandNode<Object> into, final Object source) {
            into.addChild(literal("glist").executes(context -> 1).build());
            into.addChild(literal("server")
                    .then(argument("server", StringArgumentType.word()).executes(context -> 1))
                    .build());
        }
    }

    @Test
    void readsWhatTheInjectorCopiesForTheConsole() {
        final RootCommandNode<Object> root = new RootCommandNode<>();
        CommandTrees.copy(new Manager(), root, new Object());

        assertEquals(
                List.of(
                        new CommandTree.Node("", null, null, List.of(1, 2), null),
                        new CommandTree.Node("glist", null, true, null, null),
                        new CommandTree.Node("server", null, null, List.of(3), null),
                        new CommandTree.Node("server", true, true, null, null)),
                CommandTree.of(root, CommandTrees.BRIGADIER).nodes());
    }

    @Test
    void publishesACommandAPluginRegistersAfterTheFirstReadWithoutAReload() {
        final List<String> commands = new ArrayList<>(List.of("glist"));
        final List<CommandTree> published = new ArrayList<>();
        final ManualScheduler scheduler = new ManualScheduler();
        final CommandTrees trees = new CommandTrees(
                () -> treeOf(commands),
                new CommandTreeWriter(new Published(published), "proxy"),
                scheduler,
                NOPLogger.NOP_LOGGER);

        trees.start();
        scheduler.runPending();
        scheduler.runPending();
        commands.add("lobby");
        scheduler.runPending();

        assertEquals(List.of(treeOf(List.of("glist")), treeOf(List.of("glist", "lobby"))), published);
        assertEquals(CommandTrees.EVERY, scheduler.pending().getFirst().delay());
    }

    /** A root with one executable literal per name, as a plugin registers them. */
    private static CommandTree treeOf(final List<String> names) {
        final RootCommandNode<Object> root = new RootCommandNode<>();
        names.forEach(name -> root.addChild(literal(name).executes(context -> 1).build()));
        return CommandTree.of(root, CommandTrees.BRIGADIER);
    }

    /** Keeps every tree the proxy published, in order. */
    private record Published(List<CommandTree> trees) implements CommandTreeStore {
        @Override
        public void publish(final String server, final CommandTree tree) {
            trees.add(tree);
        }

        @Override
        public Optional<CommandTree> tree(final String server) {
            return Optional.empty();
        }

        @Override
        public Map<String, Instant> published() {
            return Map.of();
        }
    }

    @Test
    void refusesACommandManagerWithoutTheInjector() {
        final IllegalStateException refused = assertThrows(
                IllegalStateException.class, () -> CommandTrees.copy(new Object(), new RootCommandNode<>(), "console"));
        assertEquals(true, refused.getMessage().startsWith("java.lang.Object hands out no command tree"));
    }
}
