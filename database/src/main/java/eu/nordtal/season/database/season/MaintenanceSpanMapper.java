package eu.nordtal.season.database.season;

import eu.nordtal.season.common.id.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps the row of {@link SeasonDao#lastMaintenance()}. */
public final class MaintenanceSpanMapper implements RowMapper<MaintenanceSpan> {

    @Override
    public MaintenanceSpan map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final Instant on = Objects.requireNonNull(SeasonStateMapper.instant(rs, "turned_on"), "turned_on");
        return new MaintenanceSpan(
                on,
                SeasonStateMapper.instant(rs, "turned_off"),
                Actor.of(Objects.requireNonNull(rs.getString("actor_kind"), "actor_kind"), rs.getString("actor_id")));
    }
}
