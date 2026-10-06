package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.database.command.CommandTree;
import eu.nordtal.season.database.command.CommandTreeStore;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A server's command tree as its console suggests from it, to signed-in admins only. */
class ConsoleCommandsApiTest extends WebTestSupport {

    @AfterEach
    void forget() throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM command_tree");
        }
    }

    @Test
    void eachServiceAnswersItsOwnTreeAndOneThatNeverPublishedAnswersNone() throws Exception {
        CommandTreeStore.using(WebFixture.postgres.dataSource())
                .publish(
                        "smp",
                        new CommandTree(List.of(
                                new CommandTree.Node("", null, null, List.of(1), null),
                                new CommandTree.Node("list", null, true, null, null))));

        final JsonObject smp = GSON.fromJson(get("/api/services/smp/commands").body(), JsonObject.class);
        assertEquals(
                "list",
                smp.getAsJsonArray("nodes").get(1).getAsJsonObject().get("name").getAsString());

        final JsonObject limbo =
                GSON.fromJson(get("/api/services/limbo/commands").body(), JsonObject.class);
        assertEquals(0, limbo.getAsJsonArray("nodes").size());
    }

    @Test
    void nobodySignedOutSeesATree() throws Exception {
        final int status = get(browser(), "/api/services/smp/commands").statusCode();

        assertTrue(status == 401 || status == 403, "answered " + status);
    }
}
