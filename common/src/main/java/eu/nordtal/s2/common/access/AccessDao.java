package eu.nordtal.s2.common.access;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole SQL surface of the access system, as a JDBI SqlObject interface. Package-private on
 * purpose: {@link AccessDirectory} is the API, and no consumer should hold a {@code Jdbi} or a DAO.
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

    /**
     * Mirrors the Discord admin role. Unlike {@code donor} this is written in both directions:
     * losing the role loses the flag, because it is a permission and not an acknowledgement.
     *
     * <p>It notifies on {@code nordtal_admin} so a revocation reaches connected sessions without
     * waiting for a reconnect. {@code pg_notify} rides inside the statement so a notification is
     * only emitted for a write that committed, and the payload is never trusted as state: the
     * listener re-reads {@link #adminDiscordIds()} in full.
     *
     * @return how many rows were notified about - always 1, and read by nothing
     */
    @SqlQuery("""
            WITH upserted AS (
                INSERT INTO discord_user (discord_id, admin, updated)
                VALUES (:discordId, :admin, now())
                ON CONFLICT (discord_id)
                    DO UPDATE SET admin = EXCLUDED.admin, updated = now()
                RETURNING discord_id
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_admin', discord_id) FROM upserted
                 )
            SELECT count(*) FROM notified
            """)
    int setAdmin(@Bind("discordId") String discordId, @Bind("admin") boolean admin);

    /**
     * Every Discord account that currently holds the admin flag. One query for the whole set rather
     * than one per connected player, which makes a roster refresh idempotent and safe on a timer.
     */
    @SqlQuery("SELECT discord_id FROM discord_user WHERE admin")
    java.util.Set<String> adminDiscordIds();

    /**
     * The Minecraft account of every admin who has one linked - a Paper server knows a session only
     * by {@link UUID}, and {@code account_link} is the only thing joining the two identities.
     *
     * <p>An admin without a link does not appear, which is correct: an unlinked account cannot get
     * past the proxy's gate, so it has no session on any backend.
     */
    @SqlQuery("SELECT l.mc_uuid FROM discord_user u"
            + " JOIN account_link l ON l.discord_id = u.discord_id"
            + " WHERE u.admin")
    java.util.Set<UUID> adminMinecraftAccounts();

    /**
     * The payment this account has started and not finished, if there is one. A read only: nothing
     * outside the bot may write {@code payment_request}, because a second writer is a second
     * half-finished purchase.
     *
     * <p>{@code bunq_tab_id IS NULL} is carried through because it is the difference between
     * "chose a number of days" and "asked for a payment link". At most one row is {@code OPEN} per
     * account in practice, but this takes the newest rather than trusting that.
     */
    @SqlQuery("SELECT reference, days, amount_cents, donation_cents,"
            + " (bunq_tab_id IS NOT NULL) AS has_tab, created"
            + " FROM payment_request"
            + " WHERE discord_id = :discordId AND status = 'OPEN'"
            + " ORDER BY created DESC LIMIT 1")
    @org.jdbi.v3.sqlobject.config.RegisterConstructorMapper(OpenPayment.class)
    Optional<OpenPayment> openPayment(@Bind("discordId") String discordId);

    // ---------------------------------------------------------------- account_link

    @SqlQuery("SELECT mc_uuid FROM account_link WHERE discord_id = :discordId")
    Optional<UUID> minecraftAccountOf(@Bind("discordId") String discordId);

    @SqlQuery("SELECT discord_id FROM account_link WHERE mc_uuid = :mcUuid")
    Optional<String> discordAccountOf(@Bind("mcUuid") UUID mcUuid);

    /**
     * Writes the 1:1 link, or does nothing if either side is already taken. The untargeted
     * {@code ON CONFLICT DO NOTHING} covers both unique constraints in one statement, so a
     * concurrent linker cannot beat a check-then-insert.
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
     *
     * <p>{@code valid_from} is {@code max(now(), season_phase.smp_start, current valid_until)}:
     * renewing early never loses paid time, buying with no access running starts now, and buying
     * before the SMP has opened starts on the day it opens. PostgreSQL computes it inside the
     * insert, from its own clock, so there is no read-then-write window two purchases could share.
     *
     * <p>Revoked and expired grants are ignored, so <b>periods are never summed</b>: somebody who
     * lapsed for a week and buys again starts today, not a week ago.
     *
     * <p>It anchors on {@code smp_start} and not on {@code launch} because access is only asked for
     * in the {@code SMP} phase; a {@code NULL} {@code smp_start} means no date has been announced
     * and the period starts now.
     *
     * <p><b>A day here is exactly 24 hours</b>, which is why the interval is built from hours.
     * {@code interval 'N days'} on a {@code timestamptz} is calendar arithmetic in the session's
     * time zone, which the JDBC driver takes from the JVM default - so a 30-day purchase spanning a
     * summer-time change would not be 30 days, and would differ between hosts.
     */
    @SqlQuery("""
            INSERT INTO access_grant (discord_id, valid_from, valid_until, source, payment_request_id)
            SELECT :discordId,
                   appended.starts_at,
                   appended.starts_at + make_interval(hours => :days * 24),
                   :source,
                   :paymentRequestId
            FROM (SELECT GREATEST(now(),
                                  COALESCE((SELECT phase.smp_start FROM season_phase phase
                                            WHERE phase.id), now()),
                                  COALESCE((SELECT max(valid_until)
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
     * Revokes the whole remaining run of access, not one grant. That is what lets
     * {@link #accessState(UUID)} use a plain {@code max(valid_until)}: the live grants of a user are
     * always one contiguous run. Revoking a single grant out of the middle is deliberately not
     * offered.
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
     * The proxy's whole login round trip, as one statement: is this UUID linked, is that Discord
     * account a non-banned member, is access active right now, what phase is the network in, and
     * when does it launch. One round trip on the login path is the requirement, which is why the
     * phase and {@code launch} ride along instead of being fetched separately.
     *
     * <p>{@code access_active} and {@code valid_until} are both needed: the first is "does a grant
     * cover this instant", the second is "when does the current run end".
     *
     * <p>It is anchored on {@code (VALUES (1))} so that it returns <b>exactly one row, always</b>.
     * Joining from {@code account_link} would return no row for an unlinked UUID, and selecting
     * {@code FROM season_phase} would make a missing phase row look like every player is unlinked.
     * With the anchor, an unlinked account is one row of nulls and an unreadable phase is a null
     * that {@code SeasonPhase.fromDatabase} maps to {@code MAINTENANCE}.
     *
     * @return the state; empty is not reachable while PostgreSQL can answer at all, and
     *         {@link AccessDirectory#accessState(UUID)} still handles it defensively
     */
    @SqlQuery("""
            SELECT cast(:mcUuid AS uuid)                                    AS mc_uuid,
                   link.discord_id,
                   usr.member_state,
                   usr.locale,
                   usr.donor,
                   usr.admin,
                   EXISTS (SELECT 1
                           FROM access_grant grant_row
                           WHERE grant_row.discord_id = link.discord_id
                             AND grant_row.revoked IS NULL
                             AND grant_row.valid_from <= now()
                             AND grant_row.valid_until > now())             AS access_active,
                   (SELECT max(grant_row.valid_until)
                    FROM access_grant grant_row
                    WHERE grant_row.discord_id = link.discord_id
                      AND grant_row.revoked IS NULL
                      AND grant_row.valid_until > now())                    AS valid_until,
                   (SELECT season.phase FROM season_phase season
                    WHERE season.id)                                        AS phase,
                   (SELECT season.launch FROM season_phase season
                    WHERE season.id)                                        AS launch
            FROM (VALUES (1)) AS anchor (one)
                     LEFT JOIN account_link link ON link.mc_uuid = cast(:mcUuid AS uuid)
                     LEFT JOIN discord_user usr ON usr.discord_id = link.discord_id
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
     * Issues a code for one Minecraft account, or hands back the one already live - a repeat
     * attempt must show the same code rather than mint another.
     *
     * <p>One statement, so two logins racing for the same UUID cannot both decide "no code exists".
     * The {@code WHERE link_code.expires <= now()} on the update clause makes it an
     * upsert-if-stale: with a live code present the insert branch matches zero rows and the guarded
     * {@code SELECT} returns the current code instead. Exactly one branch ever returns a row.
     *
     * <p>A candidate can still collide with a <em>different</em> account's live code, violating the
     * {@code code} primary key, which this {@code ON CONFLICT} target does not catch; that surfaces
     * as an exception and the caller retries with a fresh candidate.
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
