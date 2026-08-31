package eu.nordtal.s2.discordbot.access.discord;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The sweeps: who should hold the access role, and whose access is about to end or just ended.
 *
 * <h2>Why this is not in {@code :common}</h2>
 * {@code AccessDirectory} answers questions about <b>one</b> account, because that is what the
 * login path and the commands ask. These are set-shaped questions asked by background timers that
 * only the bot runs, and the proxy and the plugins have no use for them. Putting them here keeps
 * {@code :common}'s API the small thing the login path needs. They read tables {@code :common}
 * owns the meaning of, so a change to {@code access_grant} is still a change in two modules -
 * which is the documented cost of the bot owning the schema.
 * </p>
 * <p>
 * Public rather than package-private: {@link #allUsers()} is also what {@code GuildState}'s
 * startup reconcile uses to find accounts that left while the bot was down, and {@code GuildState}
 * lives at the top level, not under {@code access} - membership, locale and the admin flag are
 * bot-wide projections, not an access-only concern.
 * </p>
 */
public interface ReconcileDao {

    /** Everyone a non-revoked grant covers right now - exactly the set that should hold the role. */
    @SqlQuery("""
            SELECT DISTINCT discord_id
            FROM access_grant
            WHERE revoked IS NULL
              AND valid_from <= now()
              AND valid_until > now()
            """)
    List<String> withActiveAccess();

    /**
     * Users whose current run of access ends within the next {@code hours}.
     * <p>
     * Grouped by user and filtered on the maximum, so an appended chain produces one deadline and
     * not one per grant.
     * </p>
     */
    @SqlQuery("""
            SELECT discord_id, max(valid_until) AS valid_until
            FROM access_grant
            WHERE revoked IS NULL AND valid_until > now()
            GROUP BY discord_id
            HAVING max(valid_until) <= now() + make_interval(hours => :hours)
            """)
    @RegisterRowMapper(AccessDeadlineMapper.class)
    List<AccessDeadline> endingWithin(@Bind("hours") int hours);

    /**
     * Users whose access ran out within the last {@code hours} and has not been renewed.
     * <p>
     * The lookback exists so that a bot which was down when somebody's access expired still sends
     * the message when it comes back, rather than the moment being missed for good.
     * </p>
     */
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
     * @return 1 the first time, 0 afterwards - so a restart does not re-send yesterday's reminders
     */
    @SqlUpdate("""
            INSERT INTO expiry_notice (discord_id, valid_until, kind)
            VALUES (:discordId, :validUntil, :kind)
            ON CONFLICT (discord_id, valid_until, kind) DO NOTHING
            """)
    int noticeOnce(@Bind("discordId") String discordId,
                   @Bind("validUntil") OffsetDateTime validUntil,
                   @Bind("kind") String kind);

    /**
     * Every Discord account the bot has ever written about.
     * <p>
     * The startup reconcile needs it to find the accounts that are <b>not</b> in the guild any
     * more: a leave the bot missed while it was down produces no event to catch up on, so the only
     * way to notice is to compare what we know against who is actually there.
     * </p>
     */
    @SqlQuery("SELECT discord_id FROM discord_user")
    List<String> allUsers();

    /**
     * The language a Discord account chose, for a message that is not going to a Minecraft
     * account. {@code AccessDirectory} answers this for a UUID, because that is what the login
     * path needs; a DM has no UUID.
     */
    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    java.util.Optional<String> localeOf(@Bind("discordId") String discordId);

    /** Deletes link codes that have run out. Stage C issues them; the sweep belongs to the bot. */
    @SqlUpdate("DELETE FROM link_code WHERE expires <= now()")
    int deleteExpiredLinkCodes();

    /** Maps the two columns the deadline queries return. */
    final class AccessDeadlineMapper implements RowMapper<AccessDeadline> {

        @Override
        public AccessDeadline map(final ResultSet rs, final StatementContext ctx) throws SQLException {
            return new AccessDeadline(
                    rs.getString("discord_id"),
                    rs.getObject("valid_until", OffsetDateTime.class).toInstant());
        }
    }
}
