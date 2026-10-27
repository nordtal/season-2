package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Holds the schema to atomic columns.
 * A jsonb or array column is a published snapshot, a value read and written whole, or on a list that only shrinks.
 */
class ColumnsAreAtomicIntegrationTest {

    /**
     * The published snapshots, written and read whole, which mirror another system's structure.
     * Two of them are text: the config library's message and the WebAuthn library's JSON.
     */
    private static final Set<String> COMPOSITE = Set.of(
            "command_tree.tree",
            "game_assets.icon_index",
            "game_catalogue.catalogue",
            "game_catalogue.datapacks",
            "guild_channels.channels",
            "setting_group.defaults",
            "setting_group.environment",
            "setting_group.problem",
            "setting_group.schema",
            "steward_session.webauthn_request");

    /** The composite columns still to normalize. */
    private static final Set<String> SHRINKING =
            Set.of("bot_inbox.outcome", "bot_inbox.payload", "hunger_games_inbox.payload", "smp_inbox.payload");

    /** The composite columns that hold a value read and written whole, never queried by part. */
    private static final Set<String> WHOLE = Set.of(
            "shared_part.part",
            "document_override.document",
            "document_version.document",
            "bot_preview_document.document",
            "smp_preview_document.document",
            "hunger_games_preview_document.document");

    @Test
    void noColumnHoldsADocumentOrAListButTheOnesListedHere() throws SQLException {
        final Set<String> expected = new TreeSet<>(COMPOSITE);
        expected.addAll(SHRINKING);
        expected.addAll(WHOLE);
        final Set<String> found = new TreeSet<>();
        // A listed column of another type is found too, so the lists name only columns that exist.
        try (Connection connection = TestDatabase.fresh().dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT table_name || '.' || column_name"
                                + " FROM information_schema.columns WHERE table_schema = 'public'"
                                + " AND (data_type IN ('json', 'jsonb', 'ARRAY') OR table_name || '.' || column_name = ANY(?))")) {
            statement.setArray(1, connection.createArrayOf("text", expected.toArray()));
            try (ResultSet columns = statement.executeQuery()) {
                while (columns.next()) {
                    found.add(columns.getString(1));
                }
            }
        }
        assertEquals(expected, found);
    }
}
