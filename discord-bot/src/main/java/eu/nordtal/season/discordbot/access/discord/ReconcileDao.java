package eu.nordtal.season.discordbot.access.discord;

import eu.nordtal.season.common.id.DiscordId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface ReconcileDao {

    @SqlQuery("""
            SELECT DISTINCT discord_id
            FROM access_grant
            WHERE revoked IS NULL
              AND valid_from <= now()
              AND valid_until > now()
            """)
    List<String> withActiveAccess();

    @SqlQuery("""
            SELECT discord_id, max(valid_until) AS valid_until
            FROM access_grant
            WHERE revoked IS NULL AND valid_until > now()
            GROUP BY discord_id
            HAVING max(valid_until) <= now() + make_interval(hours => :hours)
            """)
    @RegisterRowMapper(AccessDeadlineMapper.class)
    List<AccessDeadline> endingWithin(@Bind("hours") int hours);

    @SqlQuery("""
            SELECT discord_id, max(valid_until) AS valid_until
            FROM access_grant
            WHERE revoked IS NULL
            GROUP BY discord_id
            HAVING max(valid_until) <= now()
               AND max(valid_until) > now() - make_interval(hours => :hours)
            """)
    @RegisterRowMapper(AccessDeadlineMapper.class)
    List<AccessDeadline> endedWithin(@Bind("hours") int hours);

    /**
     * Records that one message about one deadline has been sent.
     *
     * @return 1 the first time and 0 afterwards
     */
    @SqlUpdate("""
            INSERT INTO expiry_notice (discord_id, valid_until, kind)
            VALUES (:discordId, :validUntil, :kind)
            ON CONFLICT (discord_id, valid_until, kind) DO NOTHING
            """)
    int noticeOnce(
            @Bind("discordId") DiscordId discordId,
            @Bind("validUntil") OffsetDateTime validUntil,
            @Bind("kind") String kind);

    @SqlQuery("SELECT discord_id FROM discord_user")
    List<String> allUsers();

    @SqlUpdate("DELETE FROM link_code WHERE expires <= now()")
    int deleteExpiredLinkCodes();

    final class AccessDeadlineMapper implements RowMapper<AccessDeadline> {

        @Override
        public AccessDeadline map(final ResultSet rs, final StatementContext ctx) throws SQLException {
            return new AccessDeadline(
                    DiscordId.of(rs.getString("discord_id")),
                    rs.getObject("valid_until", OffsetDateTime.class).toInstant());
        }
    }
}
