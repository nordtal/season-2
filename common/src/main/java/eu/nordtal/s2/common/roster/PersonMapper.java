package eu.nordtal.s2.common.roster;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps a row of {@link RosterDao#people(int)}.
 * <p>
 * Written out rather than reached for with {@code ConstructorMapper}, for the reason
 * {@code AccessGrantMapper} gives: that mapper matches record components by parameter name, which
 * only survives compilation with {@code -parameters}, and this repository does not set it. It also
 * documents that every point in time comes back as {@code timestamptz} and is converted through
 * {@link OffsetDateTime} - the only way to get an {@link Instant} out of the PostgreSQL driver
 * without going through the JVM's default time zone.
 * </p>
 */
public final class PersonMapper implements RowMapper<Person> {

    @Override
    public Person map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new Person(
                rs.getString("discord_id"),
                rs.getString("member_state"),
                rs.getBoolean("donor"),
                rs.getBoolean("admin"),
                rs.getString("locale"),
                instant(rs, "updated"),
                rs.getObject("mc_uuid", UUID.class),
                instant(rs, "linked"),
                instant(rs, "access_until"),
                rs.getBoolean("access_active"),
                rs.getString("discord_username"),
                instant(rs, "discord_username_updated"),
                rs.getString("discord_display_name"),
                instant(rs, "discord_display_name_updated"),
                rs.getString("discord_avatar_url"),
                instant(rs, "discord_avatar_url_updated"),
                rs.getString("mc_name"),
                instant(rs, "mc_name_updated"),
                // getObject, not getLong: the latter answers 0 for SQL NULL, and zero is a play
                // time somebody could actually have.
                rs.getObject("playtime_seconds", Long.class));
    }

    /** The one conversion every mapper in this package uses; see the class comment. */
    static Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
