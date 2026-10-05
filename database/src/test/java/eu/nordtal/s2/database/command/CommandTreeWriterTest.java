package eu.nordtal.s2.database.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A reread that changed nothing costs no write, and a failed write leaves nothing taken for granted. */
class CommandTreeWriterTest {

    private static final CommandTree STOP = new CommandTree(List.of(
            new CommandTree.Node("", null, null, List.of(1), null),
            new CommandTree.Node("stop", null, true, null, null)));

    private static final CommandTree LIST = new CommandTree(List.of(
            new CommandTree.Node("", null, null, List.of(1), null),
            new CommandTree.Node("list", null, true, null, null)));

    /** Keeps what it was given, and then loses the answer while the line is bad, so a write may land unseen. */
    private static final class Store implements CommandTreeStore {
        final List<String> written = new ArrayList<>();
        boolean bad;

        @Override
        public void publish(final String server, final CommandTree tree) {
            written.add(server + ":" + tree.nodes().get(1).name());
            if (bad) {
                throw new IllegalStateException("the answer was lost");
            }
        }

        @Override
        public Optional<CommandTree> tree(final String server) {
            return Optional.empty();
        }
    }

    @Test
    void writesATreeOnceAndAgainOnlyWhenItChanged() {
        final Store store = new Store();
        final CommandTreeWriter writer = new CommandTreeWriter(store, "smp");

        assertTrue(writer.write(STOP));
        assertFalse(writer.write(new CommandTree(STOP.nodes())));
        assertTrue(writer.write(LIST));
        assertTrue(writer.write(STOP));

        assertEquals(List.of("smp:stop", "smp:list", "smp:stop"), store.written);
    }

    @Test
    void writesTheEarlierTreeAgainAfterAWriteWhoseOutcomeIsUnknown() {
        final Store store = new Store();
        final CommandTreeWriter writer = new CommandTreeWriter(store, "proxy");
        assertTrue(writer.write(STOP));

        store.bad = true;
        assertThrows(IllegalStateException.class, () -> writer.write(LIST));
        store.bad = false;

        assertTrue(writer.write(STOP));
        assertEquals(List.of("proxy:stop", "proxy:list", "proxy:stop"), store.written);
    }
}
