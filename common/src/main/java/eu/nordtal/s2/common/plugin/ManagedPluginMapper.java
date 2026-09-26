package eu.nordtal.s2.common.plugin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps a {@code service_plugin} row.
 *
 * <p>Written out rather than reached for with {@code ConstructorMapper}, for the reason
 * {@code UpdateRequestMapper} gives: that mapper matches record components by parameter name, which
 * only survives compilation with {@code -parameters}, and a build flag is a bad thing for a query
 * to depend on. {@code added} goes through {@link OffsetDateTime} for the same reason it does
 * there - the only reliable way out of the PostgreSQL driver that does not pass through the JVM's
 * default time zone.</p>
 */
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
