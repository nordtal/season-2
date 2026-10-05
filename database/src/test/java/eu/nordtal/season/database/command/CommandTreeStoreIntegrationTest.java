package eu.nordtal.season.database.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** The servers' command trees against a real PostgreSQL and the real migrations; skipped without Docker. */
class CommandTreeStoreIntegrationTest {

    private static final CommandTree TREE = new CommandTree(List.of(
            new CommandTree.Node("", null, null, List.of(1), null),
            new CommandTree.Node("stop", null, true, null, null)));

    @Test
    void eachServerWritesItsOwnTreeUnderItsOwnLoginAndAnotherWriteReplacesIt() {
        final TestDatabase database = TestDatabase.fresh();
        final Map<DatabaseRole, String> servers = Map.of(
                DatabaseRole.SMP, "smp",
                DatabaseRole.HUNGER_GAMES, "hunger-games",
                DatabaseRole.LIMBO, "limbo",
                DatabaseRole.PROXY, "proxy");
        servers.forEach((login, server) -> {
            final CommandTreeStore store = CommandTreeStore.using(database.dataSourceAs(login));
            store.publish(server, new CommandTree(List.of(new CommandTree.Node("", null, null, null, null))));
            store.publish(server, TREE);
        });

        final CommandTreeStore steward = CommandTreeStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        assertEquals(TREE, steward.tree("smp").orElseThrow());
        assertEquals(TREE, steward.tree("hunger-games").orElseThrow());
        assertEquals(TREE, steward.tree("proxy").orElseThrow());
        assertTrue(steward.tree("discord-bot").isEmpty());
    }

    @Test
    void stewardReadsTheTreesAndWritesNoneOfThem() {
        final CommandTreeStore steward =
                CommandTreeStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));

        assertThrows(UnableToExecuteStatementException.class, () -> steward.publish("smp", TREE));
    }
}
