package eu.nordtal.s2.common.access;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Locales;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Maps the single row the login query returns. See {@link AccessDao#accessState(UUID)}.
 * <p>
 * Every column except {@code mc_uuid} may be {@code SQL NULL} here, because that query outer-joins
 * an account that may not be linked onto a phase that may not be readable: an unlinked UUID comes
 * back as one row of nulls rather than as no row at all. {@code getString} answers {@code null},
 * {@code getBoolean} answers {@code false}, and {@code Locales.parse} is documented to take a
 * {@code null}. {@code SeasonPhase.fromDatabase} maps a {@code null} to {@code MAINTENANCE}, which
 * is what a missing {@code season_phase} row has to read as.
 * </p>
 * <p>
 * {@code member_state} is the one column not simply passed through its own {@code fromDatabase}:
 * that method answers {@code LEFT} for a {@code null}, while {@link AccessState} documents
 * {@code memberState} as {@code null} when the account is unlinked. "We have never heard of this
 * UUID" and "this Discord account left the guild" are different facts with different disconnect
 * screens, and collapsing them here would make the record lie about itself.
 * </p>
 */
public final class AccessStateMapper implements RowMapper<AccessState> {

    @Override
    public AccessState map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final String discordId = rs.getString("discord_id");
        return new AccessState(
                rs.getObject("mc_uuid", UUID.class),
                discordId,
                discordId == null ? null : MemberState.fromDatabase(rs.getString("member_state")),
                rs.getBoolean("access_active"),
                AccessGrantMapper.instant(rs, "valid_until"),
                rs.getBoolean("donor"),
                rs.getBoolean("admin"),
                Locales.parse(rs.getString("locale")),
                SeasonPhase.fromDatabase(rs.getString("phase")),
                AccessGrantMapper.instant(rs, "launch"));
    }
}
