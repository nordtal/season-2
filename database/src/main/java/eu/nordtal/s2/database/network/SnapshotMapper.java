package eu.nordtal.s2.database.network;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps the single row {@link SnapshotDao#snapshot(String)} returns, reading every {@code NULL} as zero or empty.
 * Alive is computed here from the two counts, so the invariant holds by construction.
 */
public final class SnapshotMapper implements RowMapper<NetworkSnapshot> {

    @Override
    public NetworkSnapshot map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final int participants = rs.getInt("hg_participants");
        final int eliminated = rs.getInt("hg_eliminated");
        return new NetworkSnapshot(
                rs.getInt("hg_teams"),
                rs.getInt("hg_teams_alive"),
                participants,
                Math.max(0, participants - eliminated),
                eliminated,
                text(rs, "smp_milestone"),
                rs.getInt("smp_progress"),
                rs.getInt("smp_milestones_done"),
                rs.getInt("smp_milestones"),
                rs.getLong("smp_aura_total"),
                rs.getInt("smp_players"));
    }

    private static String text(final ResultSet rs, final String column) throws SQLException {
        final String value = rs.getString(column);
        return value == null ? "" : value;
    }
}
