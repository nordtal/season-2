package eu.nordtal.s2.networkcontrol.ping;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the single row {@link SnapshotDao#snapshot()} returns.
 * <p>
 * Every column may be {@code SQL NULL} - no open game, no active milestone, an SMP nobody has
 * joined yet - and all of them read as zero or as an empty string, which is what a MOTD should
 * show. {@code getInt} already answers {@code 0} for a {@code NULL}, so only the two text columns
 * need saying out loud.
 * </p>
 * <p>
 * {@code alive} is computed here rather than in SQL, from the two counts the query does return:
 * one fewer subquery over {@code hg_member}, and the invariant
 * {@code alive + eliminated == participants} is then true by construction instead of by two
 * queries agreeing. Clamped at zero because a {@code DEATH} row whose member has since been
 * reworked would otherwise render a negative count in the server browser.
 * </p>
 */
public final class SnapshotMapper implements RowMapper<NetworkSnapshot> {

    @Override
    public NetworkSnapshot map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final int participants = rs.getInt("hg_participants");
        final int eliminated = rs.getInt("hg_eliminated");
        return new NetworkSnapshot(
                text(rs, "hg_state"),
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
