package eu.nordtal.s2.discordbot.access.discord;

import eu.nordtal.s2.common.id.DiscordId;
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

/**
 * The sweeps: who should hold the access role, and whose access is about to end or just ended.
 *
 * These set-shaped queries serve only the bot's timers, so they stay out of {@code :common}'s {@code AccessDirectory}.
 */
public interface ReconcileDao {

    /** Everyone a non-revoked grant covers right now, which is exactly the set that should hold the role. */
    @SqlQuery("""
            SELECT DISTINCT discord_id
            FROM access_grant
            WHERE revoked IS NULL
              AND valid_from <= now()
              AND valid_until > now()
            """)
    List<String> withActiveAccess();

    /** Returns users whose current run of access ends within the next {@code hours}, one row per user. */
    @SqlQuery("""
            SELECT discord_id, max(valid_until) AS valid_until
            FROM access_grant
            WHERE revoked IS NULL AND valid_until > now()
            GROUP BY discord_id
            HAVING max(valid_until) <= now() + make_interval(hours => :hours)
            """)
    @RegisterRowMapper(AccessDeadlineMapper.class)
    List<AccessDeadline> endingWithin(@Bind("hours") int hours);

    /** Returns users whose access ran out within the last {@code hours} and has not been renewed. */
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
     * @return 1 the first time, 0 afterwards
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

    /** Returns every Discord account the bot has ever written about, to find those that left while it was down. */
    @SqlQuery("SELECT discord_id FROM discord_user")
    List<String> allUsers();

    /** Returns the language a Discord account chose, for a DM, which has no Minecraft UUID. */
    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    java.util.Optional<String> localeOf(@Bind("discordId") DiscordId discordId);

    /** Deletes link codes that have run out. */
    @SqlUpdate("DELETE FROM link_code WHERE expires <= now()")
    int deleteExpiredLinkCodes();

    /** Maps the two columns the deadline queries return. */
    final class AccessDeadlineMapper implements RowMapper<AccessDeadline> {

        @Override
        public AccessDeadline map(final ResultSet rs, final StatementContext ctx) throws SQLException {
            return new AccessDeadline(
                    DiscordId.of(rs.getString("discord_id")),
                    rs.getObject("valid_until", OffsetDateTime.class).toInstant());
        }
    }
}
