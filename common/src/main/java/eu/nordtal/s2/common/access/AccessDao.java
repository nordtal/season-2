package eu.nordtal.s2.common.access;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole SQL surface of the access system, as a JDBI SqlObject interface - the same style as
 * the bot's {@code ContributionDao}. There is no generic CRUD layer above or below it.
 * <p>
 * Package-private on purpose: {@link AccessDirectory} is the API, this is how it is implemented,
 * and no consumer should ever hold a {@code Jdbi} or a DAO of ours.
 * </p>
 */
interface AccessDao {

    // ---------------------------------------------------------------- discord_user

    /**
     * Makes sure a row exists for this Discord account without touching an existing one. Every
     * write below has a foreign key onto {@code discord_user}, so this runs first.
     */
    @SqlUpdate("""
            INSERT INTO discord_user (discord_id)
            VALUES (:discordId)
            ON CONFLICT (discord_id) DO NOTHING
            """)
    void ensureUser(@Bind("discordId") String discordId);

    @SqlUpdate("""
            INSERT INTO discord_user (discord_id, member_state, updated)
            VALUES (:discordId, :memberState, now())
            ON CONFLICT (discord_id)
                DO UPDATE SET member_state = EXCLUDED.member_state, updated = now()
            """)
    void setMemberState(@Bind("discordId") String discordId, @Bind("memberState") String memberState);

    @SqlUpdate("""
            INSERT INTO discord_user (discord_id, locale, updated)
            VALUES (:discordId, :locale, now())
            ON CONFLICT (discord_id)
                DO UPDATE SET locale = EXCLUDED.locale, updated = now()
            """)
    void setLocale(@Bind("discordId") String discordId, @Bind("locale") String locale);

    @SqlUpdate("""
            INSERT INTO discord_user (discord_id, donor, updated)
            VALUES (:discordId, :donor, now())
            ON CONFLICT (discord_id)
                DO UPDATE SET donor = EXCLUDED.donor, updated = now()
            """)
    void setDonor(@Bind("discordId") String discordId, @Bind("donor") boolean donor);

    @SqlQuery("SELECT donor FROM discord_user WHERE discord_id = :discordId")
    Optional<Boolean> donor(@Bind("discordId") String discordId);

    // ---------------------------------------------------------------- account_link

    @SqlQuery("SELECT mc_uuid FROM account_link WHERE discord_id = :discordId")
    Optional<UUID> minecraftAccountOf(@Bind("discordId") String discordId);

    @SqlQuery("SELECT discord_id FROM account_link WHERE mc_uuid = :mcUuid")
    Optional<String> discordAccountOf(@Bind("mcUuid") UUID mcUuid);

    /**
     * Writes the 1:1 link, or does nothing if either side is already taken.
     * <p>
     * {@code ON CONFLICT DO NOTHING} without a conflict target covers both unique constraints -
     * the {@code discord_id} primary key and the {@code mc_uuid} unique index - so this is one
     * statement rather than a check followed by an insert that a concurrent linker could beat.
     * </p>
     *
     * @return 1 when the link was written, 0 when either side was already linked
     */
    @SqlUpdate("""
            INSERT INTO account_link (discord_id, mc_uuid)
            VALUES (:discordId, :mcUuid)
            ON CONFLICT DO NOTHING
            """)
    int link(@Bind("discordId") String discordId, @Bind("mcUuid") UUID mcUuid);

    @SqlUpdate("DELETE FROM account_link WHERE discord_id = :discordId")
    int unlink(@Bind("discordId") String discordId);

    // ---------------------------------------------------------------- access_grant

    /**
     * The append rule, as one statement.
     * <p>
     * {@code valid_from} is {@code max(now(), current valid_until)}: renewing early never loses
     * paid time, and buying with no access running starts now. It is computed by PostgreSQL from
     * PostgreSQL's own clock, inside the insert - two callers cannot each implement their own
     * version of it, and there is no read-then-write window in which a second purchase could read
     * the same {@code valid_until}.
     * </p>
     * <p>
     * The subquery deliberately ignores revoked grants and grants that have already run out, so a
     * user whose access lapsed in March does not get a period starting in March.
     * </p>
     * <p>
     * <b>A day here is exactly 24 hours</b>, which is why the interval is built from hours and not
     * from {@code days}. Adding {@code interval 'N days'} to a {@code timestamptz} is <i>calendar</i>
     * arithmetic evaluated in the session's time zone, and the JDBC driver sets that time zone from
     * the JVM's default - so the same 30-day purchase would have been 30 days and one hour when it
     * spanned the end of European summer time, and would have differed between the bot's host and
     * the proxy's host if their time zones ever diverged. Hours are exact and time-zone free.
     * Caught by {@code AccessDirectoryIntegrationTest} on 2026-08-30, not by reading the docs.
     * </p>
     */
    @SqlQuery("""
            INSERT INTO access_grant (discord_id, valid_from, valid_until, source, payment_request_id)
            SELECT :discordId,
                   appended.starts_at,
                   appended.starts_at + make_interval(hours => :days * 24),
                   :source,
                   :paymentRequestId
            FROM (SELECT GREATEST(now(), COALESCE((SELECT max(valid_until)
                                                   FROM access_grant
                                                   WHERE discord_id = :discordId
                                                     AND revoked IS NULL
                                                     AND valid_until > now()), now())) AS starts_at) appended
            RETURNING id, discord_id, valid_from, valid_until, source, payment_request_id, revoked, created
            """)
    @RegisterRowMapper(AccessGrantMapper.class)
    AccessGrant grantAccess(@Bind("discordId") String discordId,
                            @Bind("days") int days,
                            @Bind("source") String source,
                            @Bind("paymentRequestId") UUID paymentRequestId);

    /**
     * Revokes the whole remaining run of access, not one grant.
     * <p>
     * That is what makes {@link #accessState(UUID)} correct with a plain {@code max(valid_until)}:
     * because a revoke always takes the tail, the non-revoked, not-yet-expired grants of a user
     * are always one contiguous run starting now, so their maximum end is the end of access.
     * Revoking a single grant out of the middle would break that and is deliberately not offered.
     * </p>
     *
     * @return how many grants were revoked
     */
    @SqlUpdate("""
            UPDATE access_grant
            SET revoked = now()
            WHERE discord_id = :discordId
              AND revoked IS NULL
              AND valid_until > now()
            """)
    int revokeAccess(@Bind("discordId") String discordId);

    @SqlQuery("""
            SELECT id, discord_id, valid_from, valid_until, source, payment_request_id, revoked, created
            FROM access_grant
            WHERE discord_id = :discordId
            ORDER BY valid_from ASC, created ASC
            """)
    @RegisterRowMapper(AccessGrantMapper.class)
    java.util.List<AccessGrant> grantsOf(@Bind("discordId") String discordId);

    // ---------------------------------------------------------------- the login path

    /**
     * The proxy's three questions in one query: is this UUID linked, is that Discord account a
     * non-banned member, and is access active right now.
     * <p>
     * {@code access_active} and {@code valid_until} are two different things and both are needed:
     * the first is "does a grant cover this instant", the second is "when does the current run
     * end", which is what the disconnect screen and {@code /access-status} print.
     * </p>
     *
     * @return empty when the UUID is not linked to any Discord account
     */
    @SqlQuery("""
            SELECT link.mc_uuid,
                   link.discord_id,
                   usr.member_state,
                   usr.locale,
                   usr.donor,
                   EXISTS (SELECT 1
                           FROM access_grant grant_row
                           WHERE grant_row.discord_id = link.discord_id
                             AND grant_row.revoked IS NULL
                             AND grant_row.valid_from <= now()
                             AND grant_row.valid_until > now()) AS access_active,
                   (SELECT max(grant_row.valid_until)
                    FROM access_grant grant_row
                    WHERE grant_row.discord_id = link.discord_id
                      AND grant_row.revoked IS NULL
                      AND grant_row.valid_until > now())        AS valid_until
            FROM account_link link
                     JOIN discord_user usr ON usr.discord_id = link.discord_id
            WHERE link.mc_uuid = :mcUuid
            """)
    @RegisterRowMapper(AccessStateMapper.class)
    Optional<AccessState> accessState(@Bind("mcUuid") UUID mcUuid);

    @SqlQuery("""
            SELECT usr.locale
            FROM account_link link
                     JOIN discord_user usr ON usr.discord_id = link.discord_id
            WHERE link.mc_uuid = :mcUuid
            """)
    Optional<String> localeOf(@Bind("mcUuid") UUID mcUuid);

    // ---------------------------------------------------------------- link_code

    /**
     * Issues a code for one Minecraft account, or hands back the one already live - stage C's
     * "a repeat attempt shows the same code rather than minting another".
     * <p>
     * One statement, so two logins racing for the same UUID cannot both decide "no code exists"
     * and each write one: {@code link_code.mc_uuid} is {@code UNIQUE}, so the second writer either
     * blocks on the first's row lock and then re-evaluates the {@code WHERE}, or (if the first one
     * left a still-valid code) falls straight through to the {@code SELECT} half and returns that
     * one instead of writing anything.
     * </p>
     * <p>
     * The {@code WHERE link_code.expires <= now()} on the update clause is what makes this an
     * upsert-if-stale rather than an unconditional overwrite: when a live code already exists, the
     * {@code INSERT ... ON CONFLICT} branch matches zero rows (the update's WHERE excludes it), so
     * the CTE returns nothing and the plain {@code SELECT} below - guarded by
     * {@code NOT EXISTS (SELECT 1 FROM upsert)} - reads back the code that is actually current.
     * Exactly one of the two branches ever returns a row.
     * </p>
     * <p>
     * A candidate code can still collide with a <em>different</em> account's still-live code - a
     * violation of the {@code code} primary key, which this statement's {@code ON CONFLICT} target
     * (scoped to {@code mc_uuid}) does not catch. That surfaces as a thrown exception; the caller
     * retries with a freshly generated candidate.
     * </p>
     */
    @SqlQuery("""
            WITH upsert AS (
                INSERT INTO link_code (code, mc_uuid, expires)
                VALUES (:code, :mcUuid, :expires)
                ON CONFLICT (mc_uuid) DO UPDATE
                    SET code = EXCLUDED.code, created = now(), expires = EXCLUDED.expires
                    WHERE link_code.expires <= now()
                RETURNING code, mc_uuid, expires
            )
            SELECT code, mc_uuid, expires FROM upsert
            UNION ALL
            SELECT code, mc_uuid, expires FROM link_code
            WHERE mc_uuid = :mcUuid AND NOT EXISTS (SELECT 1 FROM upsert)
            """)
    @RegisterRowMapper(LinkCodeMapper.class)
    LinkCode upsertLinkCode(@Bind("code") String code, @Bind("mcUuid") UUID mcUuid, @Bind("expires") Instant expires);

    /** @return the Minecraft account the code was issued for, empty when unknown or expired */
    @SqlQuery("SELECT mc_uuid FROM link_code WHERE code = :code AND expires > now()")
    Optional<UUID> mcUuidForActiveCode(@Bind("code") String code);

    /**
     * Deletes one code, only once it has actually been redeemed - a failed redemption (an already
     * linked account, say) leaves the code alone so a legitimate retry is not punished.
     */
    @SqlUpdate("DELETE FROM link_code WHERE code = :code")
    int deleteLinkCode(@Bind("code") String code);
}
