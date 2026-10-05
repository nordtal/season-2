package eu.nordtal.s2.proxy.command;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.RootCommandNode;
import eu.nordtal.s2.database.command.CommandTree;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    void refusesACommandManagerWithoutTheInjector() {
        final IllegalStateException refused = assertThrows(
                IllegalStateException.class, () -> CommandTrees.copy(new Object(), new RootCommandNode<>(), "console"));
        assertEquals(true, refused.getMessage().startsWith("java.lang.Object hands out no command tree"));
    }
}
