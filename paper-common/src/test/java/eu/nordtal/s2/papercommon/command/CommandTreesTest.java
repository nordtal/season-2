package eu.nordtal.s2.papercommon.command;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.RootCommandNode;
import eu.nordtal.s2.database.command.CommandTree;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A real Brigadier dispatcher read into a tree, and the game's dispatcher found behind Paper's mirror of it. */
class CommandTreesTest {

    /** As Paper's ApiMirrorRootNode: a public class declaring the dispatcher, extended by one that is not public. */
    public abstract static class MirrorRoot extends RootCommandNode<Object> {
        public abstract CommandDispatcher<Object> getDispatcher();
    }

    private static final class Mirror extends MirrorRoot {
        private final CommandDispatcher<Object> game;

        Mirror(final CommandDispatcher<Object> game) {
            this.game = game;
        }

        @Override
        public CommandDispatcher<Object> getDispatcher() {
            return game;
        }
    }

    @Test
    void readsEveryWordAndArgumentWithWhatRunsAndWhereARedirectLeads() {
        final CommandDispatcher<Object> game = new CommandDispatcher<>();
        game.register(literal("give")
                .then(argument("targets", StringArgumentType.word())
                        .then(argument("count", IntegerArgumentType.integer()).executes(context -> 1))));
        game.register(literal("execute").then(literal("run").redirect(game.getRoot())));
        game.register(literal("stop").executes(context -> 1));

        final CommandTree tree = CommandTree.of(game.getRoot(), CommandTrees.BRIGADIER);

        assertEquals(
                List.of(
                        new CommandTree.Node("", null, null, List.of(1, 2, 3), null),
                        new CommandTree.Node("execute", null, null, List.of(4), null),
                        new CommandTree.Node("give", null, null, List.of(5), null),
                        new CommandTree.Node("stop", null, true, null, null),
                        new CommandTree.Node("run", null, null, null, 0),
                        new CommandTree.Node("targets", true, null, List.of(6), null),
                        new CommandTree.Node("count", true, true, null, null)),
                tree.nodes());
    }

    @Test
    void findsTheGamesDispatcherThroughThePublicClassTheMirrorExtends() {
        final CommandDispatcher<Object> game = new CommandDispatcher<>();
        final CommandDispatcher<Object> mirror = new CommandDispatcher<>(new Mirror(game));

        assertSame(game, CommandTrees.game(mirror));
    }

    @Test
    void refusesARootThatNamesNoDispatcher() {
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> CommandTrees.game(new CommandDispatcher<>()));
        assertEquals(RootCommandNode.class.getName() + " names no dispatcher", refused.getMessage());
    }
}
