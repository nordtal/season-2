package eu.nordtal.season.database.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.common.json.Json;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The walk both platforms hand their dispatcher to, over a hand-built tree in Brigadier's shape. */
class CommandTreeTest {

    /** A node as Brigadier has one: a name, a kind, whether it runs, its children and where it redirects. */
    private static final class Fake {
        final String name;
        final boolean argument;
        final boolean executes;
        final List<Fake> children = new ArrayList<>();

        @Nullable
        Fake redirect;

        Fake(final String name, final boolean argument, final boolean executes) {
            this.name = name;
            this.argument = argument;
            this.executes = executes;
        }

        Fake then(final Fake child) {
            children.add(child);
            return this;
        }
    }

    private static final CommandTree.Shape<Fake> SHAPE = new CommandTree.Shape<>() {
        @Override
        public String name(final Fake node) {
            return node.name;
        }

        @Override
        public boolean argument(final Fake node) {
            return node.argument;
        }

        @Override
        public boolean executes(final Fake node) {
            return node.executes;
        }

        @Override
        public Collection<Fake> children(final Fake node) {
            return node.children;
        }

        @Override
        public @Nullable Fake redirect(final Fake node) {
            return node.redirect;
        }
    };

    private static Fake word(final String name) {
        return new Fake(name, false, false);
    }

    private static Fake argument(final String name) {
        return new Fake(name, true, true);
    }

    private static List<String> names(final CommandTree tree, final int index) {
        final List<Integer> children = tree.nodes().get(index).children();
        return children == null
                ? List.of()
                : children.stream().map(child -> tree.nodes().get(child).name()).toList();
    }

    @Test
    void wordsComeBeforeArgumentsAndPlainWordsBeforeNamespacedOnes() {
        final Fake root = word("");
        root.then(word("minecraft:give"))
                .then(word("tp").then(argument("targets")).then(word("@s")))
                .then(word("give"));

        final CommandTree tree = CommandTree.of(root, SHAPE);

        assertEquals(List.of("give", "tp", "minecraft:give"), names(tree, 0));
        assertEquals(List.of("@s", "targets"), names(tree, 2));
        assertEquals("", tree.nodes().get(0).name());
    }

    @Test
    void anAbsentComponentIsLeftOutRatherThanFalse() {
        final Fake root = word("");
        root.then(word("list").then(argument("uuids")));
        root.children.getFirst().children.getFirst().children.clear();

        final CommandTree tree = CommandTree.of(root, SHAPE);

        assertEquals(
                new CommandTree.Node("list", null, null, List.of(2), null),
                tree.nodes().get(1));
        assertEquals(
                new CommandTree.Node("uuids", true, true, null, null),
                tree.nodes().get(2));
        assertEquals(
                "{\"name\":\"list\",\"children\":[2]}", Json.encode(tree.nodes().get(1)));
    }

    @Test
    void aRedirectToTheRootIsOneIndexSoTheTreeStaysFinite() {
        final Fake root = word("");
        final Fake run = word("run");
        run.redirect = root;
        final Fake as = word("as");
        final Fake execute = word("execute").then(run).then(as);
        as.then(argument("targets"));
        as.children.getFirst().redirect = execute;
        root.then(execute);

        final CommandTree tree = CommandTree.of(root, SHAPE);

        assertEquals(
                List.of("", "execute", "as", "run", "targets"),
                tree.nodes().stream().map(CommandTree.Node::name).toList());
        assertEquals(0, tree.nodes().get(3).redirect());
        assertEquals(1, tree.nodes().get(4).redirect());
        assertNull(tree.nodes().get(3).children());
    }

    @Test
    void aNodeReachedFromTwoParentsIsIndexedOnce() {
        final Fake shared = argument("message");
        final Fake root = word("");
        root.then(word("say").then(shared)).then(word("me").then(shared));

        final CommandTree tree = CommandTree.of(root, SHAPE);

        assertEquals(4, tree.nodes().size());
        assertEquals(tree.nodes().get(1).children(), tree.nodes().get(2).children());
    }

    @Test
    void readsBackFromItsJsonAsItWasWritten() {
        final Fake root = word("");
        final Fake run = word("run");
        run.redirect = root;
        root.then(word("execute").then(run)).then(word("stop"));
        final CommandTree tree = CommandTree.of(root, SHAPE);

        assertEquals(tree, Json.decode(Json.encode(tree), CommandTree.class));
    }

    @Test
    void aTreeHasAtLeastItsRoot() {
        assertThrows(IllegalArgumentException.class, () -> new CommandTree(List.of()));
        assertEquals(1, CommandTree.of(word(""), SHAPE).nodes().size());
    }
}
