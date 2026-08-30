package eu.nordtal.s2.common.access;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Maps a {@code link_code} row. See {@link AccessGrantMapper} for why this is written by hand. */
public final class LinkCodeMapper implements RowMapper<LinkCode> {

    @Override
    public LinkCode map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new LinkCode(
                rs.getString("code"),
                rs.getObject("mc_uuid", UUID.class),
                AccessGrantMapper.instant(rs, "expires"));
    }
}
