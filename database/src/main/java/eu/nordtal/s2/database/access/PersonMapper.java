package eu.nordtal.s2.database.access;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.language.Locales;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/** Maps a row of {@link RosterDao#people(int)}, reading every point in time through {@link OffsetDateTime}. */
public final class PersonMapper implements RowMapper<Person> {

    @Override
    public Person map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new Person(
                DiscordId.of(rs.getString("discord_id")),
                rs.getString("member_state"),
                rs.getBoolean("donor"),
                rs.getBoolean("admin"),
                Locales.tag(Locales.parse(rs.getString("locale"))),
                Objects.requireNonNull(instant(rs, "updated"), "updated"),
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
                // getObject, since getLong answers 0 for NULL and zero is a real play time.
                rs.getObject("playtime_seconds", Long.class),
                rs.getString("admin_granted_by"),
                instant(rs, "admin_granted_at"),
                rs.getString("pack_exempt_by"),
                instant(rs, "pack_exempt_at"));
    }

    /** Converts a {@code timestamptz} column without the JVM's default time zone. */
    static @Nullable Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
