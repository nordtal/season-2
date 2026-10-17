package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Holds the schema to atomic columns: a jsonb or array column exists only on a list that only shrinks. */
class ColumnsAreAtomicIntegrationTest {

    /** The composite columns the schema still has; a change that normalizes one removes it here. */
    private static final Set<String> COMPOSITE = Set.of(
            "admin_alert.detail",
            "admin_alert.measured",
            "admin_alert.title",
            "audit_log.line",
            "bank_inbox.outcome",
            "bot_inbox.outcome",
            "bot_inbox.payload",
            "command_tree.tree",
            "game_assets.icon_index",
            "game_catalogue.catalogue",
            "game_catalogue.datapacks",
            "guild_channels.channels",
            "hunger_games_inbox.outcome",
            "hunger_games_inbox.payload",
            "setting_group.defaults",
            "setting_group.environment",
            "setting_group.schema",
            "setting_override.value",
            "smp_inbox.outcome",
            "smp_inbox.payload",
            "steward_inbox.moving",
            "steward_inbox.outcome",
            "steward_inbox.payload");

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
        assertEquals(new TreeSet<>(COMPOSITE), found);
    }
}
