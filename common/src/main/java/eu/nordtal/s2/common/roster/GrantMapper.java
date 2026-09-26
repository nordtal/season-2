package eu.nordtal.s2.common.roster;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps an {@code access_grant} row; see {@link PersonMapper} for why it is written out. */
public final class GrantMapper implements RowMapper<Grant> {

    @Override
    public Grant map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new Grant(
                rs.getObject("id", UUID.class),
                rs.getString("discord_id"),
                Objects.requireNonNull(PersonMapper.instant(rs, "valid_from"), "valid_from"),
                Objects.requireNonNull(PersonMapper.instant(rs, "valid_until"), "valid_until"),
                rs.getString("source"),
                rs.getObject("payment_request_id", UUID.class),
                PersonMapper.instant(rs, "revoked"),
                Objects.requireNonNull(PersonMapper.instant(rs, "created"), "created"));
    }
}
