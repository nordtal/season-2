package eu.nordtal.s2.steward.worker.plugin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps a {@code service_plugin} row, written out so it does not depend on the {@code -parameters} flag. */
public final class ManagedPluginMapper implements RowMapper<ManagedPlugin> {

    @Override
    public ManagedPlugin map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new ManagedPlugin(
                rs.getString("service"),
                rs.getString("artifact"),
                rs.getString("project_id"),
                rs.getString("file_prefix"),
                rs.getString("title"),
                rs.getString("icon_url"),
                rs.getString("page_url"),
                rs.getObject("added", OffsetDateTime.class).toInstant(),
                rs.getString("added_by"));
    }
}
