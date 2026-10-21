package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Holds the schema to atomic columns.
 * A jsonb or array column exists only on a list that only shrinks, or holds a value that is read and written whole.
 */
class ColumnsAreAtomicIntegrationTest {

    /** The composite columns the schema still has. */
    private static final Set<String> COMPOSITE = Set.of(
            "bot_inbox.outcome",
            "bot_inbox.payload",
            "command_tree.tree",
            "game_assets.icon_index",
            "game_catalogue.catalogue",
            "game_catalogue.datapacks",
            "guild_channels.channels",
            "hunger_games_inbox.payload",
            "setting_group.defaults",
            "setting_group.environment",
            "setting_group.schema",
            "setting_override.value",
            "smp_inbox.payload",
            "steward_inbox.moving",
            "steward_inbox.outcome",
            "steward_inbox.payload");

    /** The composite columns that hold a value read and written whole, never queried by part. */
    private static final Set<String> WHOLE =
            Set.of("shared_part.part", "document_override.document", "document_version.document");

    @Test
    void noColumnHoldsADocumentOrAListButTheOnesStillToNormalize() throws SQLException {
        final Set<String> found = new TreeSet<>();
        try (Connection connection = TestDatabase.fresh().dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery("SELECT table_name || '.' || column_name FROM"
                        + " information_schema.columns WHERE table_schema = 'public'"
                        + " AND data_type IN ('json', 'jsonb', 'ARRAY')")) {
            while (columns.next()) {
                found.add(columns.getString(1));
            }
        }
        final Set<String> expected = new TreeSet<>(COMPOSITE);
        expected.addAll(WHOLE);
        assertEquals(expected, found);
    }
}
