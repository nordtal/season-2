package eu.nordtal.s2.common.access;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL surface of the access system; {@link AccessDirectory} is the API. */
interface AccessDao {

    /** Ensures a row exists for this Discord account without touching an existing one. */
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

    /**
     * Sets total play time to an absolute number of seconds.
     *
     * Deliberately not the addition {@code PlaytimeDao#add} performs on the proxy: this is an
     * admin saying what the number <em>is</em>, which is the only way to reach a prestige tier on
     * purpose - the tier is derived from this column and stored nowhere. The two writers do not
     * conflict, they disagree: a flush that lands while somebody is online adds to whatever this
     * wrote, so an override on an account that is playing right now keeps ticking up from the new
     * value.
     */
    @SqlUpdate("""
            INSERT INTO player_playtime (discord_id, seconds, updated)
            VALUES (:discordId, :seconds, now())
            ON CONFLICT (discord_id) DO UPDATE
                SET seconds = EXCLUDED.seconds,
                    updated = now()
            """)
    void setPlaytimeSeconds(@Bind("discordId") String discordId, @Bind("seconds") long seconds);

    @SqlQuery("SELECT donor FROM discord_user WHERE discord_id = :discordId")
    Optional<Boolean> donor(@Bind("discordId") String discordId);

    /**
     * Writes all three Discord-observed fields at once, each with its own {@code now()} timestamp.
     * Called from {@code GuildState}'s reconcile pass and its join handler, which already visit one
     * member at a time and already know the row exists - there is deliberately no separate loop for
     * this.
     */
    @SqlUpdate("""
            INSERT INTO discord_user (discord_id, discord_username, discord_username_updated,
                                       discord_display_name, discord_display_name_updated,
                                       discord_avatar_url, discord_avatar_url_updated, updated)
            VALUES (:discordId, :username, now(), :displayName, now(), :avatarUrl, now(), now())
            ON CONFLICT (discord_id)
                DO UPDATE SET discord_username = EXCLUDED.discord_username,
                              discord_username_updated = now(),
                              discord_display_name = EXCLUDED.discord_display_name,
                              discord_display_name_updated = now(),
                              discord_avatar_url = EXCLUDED.discord_avatar_url,
                              discord_avatar_url_updated = now(),
                              updated = now()
            """)
    void setDiscordProfile(
            @Bind("discordId") String discordId,
            @Bind("username") String username,
            @Bind("displayName") String displayName,
            @Bind("avatarUrl") String avatarUrl);

    /**
     * Clears the cached name and avatar of an account that left or was banned.
     *
     * {@code discord_username} is kept: it is the account's own name, so it goes stale rather than wrong.
     */
    @SqlUpdate("""
            UPDATE discord_user
            SET discord_display_name = NULL, discord_display_name_updated = now(),
                discord_avatar_url = NULL, discord_avatar_url_updated = now(),
                updated = now()
            WHERE discord_id = :discordId
            """)
    void clearGuildProfile(@Bind("discordId") String discordId);

    /** @return what was last observed about this account's Discord profile; empty for no such row */
    @SqlQuery("""
            SELECT discord_username, discord_username_updated,
                   discord_display_name, discord_display_name_updated,
                   discord_avatar_url, discord_avatar_url_updated
            FROM discord_user
            WHERE discord_id = :discordId
            """)
    @RegisterRowMapper(DiscordProfileMapper.class)
    Optional<DiscordProfile> discordProfile(@Bind("discordId") String discordId);

    /** Returns every Discord account that currently holds the admin flag. */
    @SqlQuery("SELECT discord_id FROM discord_user WHERE admin")
    java.util.Set<String> adminDiscordIds();

    /** Returns the Minecraft account of every admin who has one linked. */
    @SqlQuery("SELECT l.mc_uuid FROM discord_user u"
            + " JOIN account_link l ON l.discord_id = u.discord_id"
            + " WHERE u.admin")
    java.util.Set<UUID> adminMinecraftAccounts();

    /**
     * Returns the newest open payment of this account, if any.
     *
     * Read only: nothing outside the bot writes {@code payment_request}.
     */
    @SqlQuery("SELECT reference, days, amount_cents, donation_cents,"
            + " (bunq_tab_id IS NOT NULL) AS has_tab, created"
            + " FROM payment_request"
            + " WHERE discord_id = :discordId AND status = 'OPEN'"
            + " ORDER BY created DESC LIMIT 1")
    @org.jdbi.v3.sqlobject.config.RegisterConstructorMapper(OpenPayment.class)
    Optional<OpenPayment> openPayment(@Bind("discordId") String discordId);

    @SqlQuery("SELECT mc_uuid FROM account_link WHERE discord_id = :discordId")
    Optional<UUID> minecraftAccountOf(@Bind("discordId") String discordId);

    @SqlQuery("SELECT discord_id FROM account_link WHERE mc_uuid = :mcUuid")
    Optional<String> discordAccountOf(@Bind("mcUuid") UUID mcUuid);

    /**
     * Writes the 1:1 link, or does nothing if either side is already taken.
     *
     * The untargeted {@code ON CONFLICT DO NOTHING} covers both unique constraints in one statement.
     *
     * @return 1 when the link was written, 0 otherwise
     */
    @SqlUpdate("""
            INSERT INTO account_link (discord_id, mc_uuid)
            VALUES (:discordId, :mcUuid)
            ON CONFLICT DO NOTHING
            """)
    int link(@Bind("discordId") String discordId, @Bind("mcUuid") UUID mcUuid);

    @SqlUpdate("DELETE FROM account_link WHERE discord_id = :discordId")
    int unlink(@Bind("discordId") String discordId);

    /**
     * Writes the Minecraft name last seen at login, keyed by the account.
     *
     * @return how many rows were touched, {@code 0} when {@code mcUuid} is not linked
     */
    @SqlUpdate("""
            UPDATE account_link
            SET mc_name = :name, mc_name_updated = now()
            WHERE mc_uuid = :mcUuid
            """)
    int setMinecraftName(@Bind("mcUuid") java.util.UUID mcUuid, @Bind("name") String name);

    /** @return what was last observed about the Minecraft account linked to this Discord id */
    @SqlQuery("SELECT mc_name, mc_name_updated FROM account_link WHERE discord_id = :discordId")
    @RegisterRowMapper(MinecraftProfileMapper.class)
    Optional<MinecraftProfile> minecraftProfile(@Bind("discordId") String discordId);

    /**
     * The append rule, as one statement.
     *
     * {@code valid_from} is {@code max(now(), season_phase.smp_start, current valid_until)}:
     * renewing early never loses paid time, buying with no access running starts now, and buying
     * before the SMP has opened starts on the day it opens. PostgreSQL computes it inside the
     * insert, from its own clock, so there is no read-then-write window two purchases could share.
     *
     * Revoked and expired grants are ignored, so <b>periods are never summed</b>: somebody who
     * lapsed for a week and buys again starts today, not a week ago.
     *
     * It anchors on {@code smp_start} and not on {@code launch} because access is only asked for
     * in the {@code SMP} phase; a {@code NULL} {@code smp_start} means no date has been announced
     * and the period starts now.
     *
     * <b>A day here is exactly 24 hours</b>, which is why the interval is built from hours.
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
    AccessGrant grantAccess(
            @Bind("discordId") String discordId,
            @Bind("days") int days,
            @Bind("source") String source,
            @Bind("paymentRequestId") @Nullable UUID paymentRequestId);

    /**
     * Revokes the whole remaining run of access, not one grant.
     *
     * That keeps the live grants contiguous, which {@link #accessState(UUID)} relies on for {@code max(valid_until)}.
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

    /**
     * Returns the proxy's whole login state in one statement.
     *
     * Anchored on {@code (VALUES (1))} so it returns exactly one row: an unlinked account is a row of nulls,
     * and a missing phase is a null that {@code SeasonPhase.fromDatabase} maps to {@code MAINTENANCE}.
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

    /**
     * Issues a code for one Minecraft account, or returns the one already live.
     *
     * One statement, so racing logins cannot both mint a code. A collision with another account's code
     * violates the primary key, which this {@code ON CONFLICT} does not catch; the caller retries.
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

    /** Deletes a code once it has been redeemed. */
    @SqlUpdate("DELETE FROM link_code WHERE code = :code")
    int deleteLinkCode(@Bind("code") String code);
}
