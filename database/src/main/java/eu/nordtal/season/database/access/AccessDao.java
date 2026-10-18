package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL of the access system. {@link AccessDirectory} is its API. */
interface AccessDao {

    /** Creates the row of a Discord account if it is missing. */
    @SqlUpdate("""
            INSERT INTO discord_user (discord_id)
            VALUES (:discordId)
            ON CONFLICT (discord_id) DO NOTHING
            """)
    void ensureUser(@Bind("discordId") DiscordId discordId);

    @SqlUpdate("""
            INSERT INTO discord_user (discord_id, member_state, updated)
            VALUES (:discordId, :memberState, now())
            ON CONFLICT (discord_id)
                DO UPDATE SET member_state = EXCLUDED.member_state, updated = now()
            """)
    void setMemberState(@Bind("discordId") DiscordId discordId, @Bind("memberState") String memberState);

    @SqlUpdate("""
            INSERT INTO discord_user (discord_id, locale, updated)
            VALUES (:discordId, :locale, now())
            ON CONFLICT (discord_id)
                DO UPDATE SET locale = EXCLUDED.locale, updated = now()
            """)
    void setLocale(@Bind("discordId") DiscordId discordId, @Bind("locale") @Nullable String locale);

    @SqlUpdate("""
            INSERT INTO discord_user (discord_id, time_zone, updated)
            VALUES (:discordId, :zone, now())
            ON CONFLICT (discord_id)
                DO UPDATE SET time_zone = EXCLUDED.time_zone, updated = now()
            """)
    void setTimeZone(@Bind("discordId") DiscordId discordId, @Bind("zone") @Nullable String zone);

    @SqlUpdate("""
            INSERT INTO discord_user (discord_id, donor, updated)
            VALUES (:discordId, :donor, now())
            ON CONFLICT (discord_id)
                DO UPDATE SET donor = EXCLUDED.donor, updated = now()
            """)
    void setDonor(@Bind("discordId") DiscordId discordId, @Bind("donor") boolean donor);

    /** Sets the total play time to an absolute value. Unlike the additive flush of the proxy, it overrides. */
    @SqlUpdate("""
            INSERT INTO player_playtime (discord_id, seconds, updated)
            VALUES (:discordId, :seconds, now())
            ON CONFLICT (discord_id) DO UPDATE
                SET seconds = EXCLUDED.seconds,
                    updated = now()
            """)
    void setPlaytimeSeconds(@Bind("discordId") DiscordId discordId, @Bind("seconds") long seconds);

    @SqlQuery("SELECT donor FROM discord_user WHERE discord_id = :discordId")
    Optional<Boolean> donor(@Bind("discordId") DiscordId discordId);

    /** Writes the three Discord-observed fields at once, each with its own {@code now()}. */
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
            @Bind("discordId") DiscordId discordId,
            @Bind("username") String username,
            @Bind("displayName") String displayName,
            @Bind("avatarUrl") String avatarUrl);

    /** Clears the cached name and avatar of an account that left or was banned. {@code discord_username} stays. */
    @SqlUpdate("""
            UPDATE discord_user
            SET discord_display_name = NULL, discord_display_name_updated = now(),
                discord_avatar_url = NULL, discord_avatar_url_updated = now(),
                updated = now()
            WHERE discord_id = :discordId
            """)
    void clearGuildProfile(@Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT discord_username, discord_username_updated,
                   discord_display_name, discord_display_name_updated,
                   discord_avatar_url, discord_avatar_url_updated
            FROM discord_user
            WHERE discord_id = :discordId
            """)
    @RegisterRowMapper(DiscordProfileMapper.class)
    Optional<DiscordProfile> discordProfile(@Bind("discordId") DiscordId discordId);

    @SqlQuery("SELECT discord_id FROM discord_user WHERE admin")
    java.util.Set<String> adminDiscordIds();

    @SqlQuery("SELECT l.mc_uuid FROM discord_user u"
            + " JOIN account_link l ON l.discord_id = u.discord_id"
            + " WHERE u.admin")
    java.util.Set<UUID> adminMinecraftAccounts();

    /** Returns the newest open payment of this account. It only reads. */
    @SqlQuery("SELECT reference, days, amount_cents, donation_cents,"
            + " (bunq_tab_id IS NOT NULL) AS has_tab, created"
            + " FROM payment_request"
            + " WHERE discord_id = :discordId AND status = 'OPEN'"
            + " ORDER BY created DESC LIMIT 1")
    @org.jdbi.v3.sqlobject.config.RegisterConstructorMapper(OpenPayment.class)
    Optional<OpenPayment> openPayment(@Bind("discordId") DiscordId discordId);

    @SqlQuery("SELECT mc_uuid FROM account_link WHERE discord_id = :discordId")
    Optional<UUID> minecraftAccountOf(@Bind("discordId") DiscordId discordId);

    @SqlQuery("SELECT discord_id FROM account_link WHERE mc_uuid = :mcUuid")
    Optional<DiscordId> discordAccountOf(@Bind("mcUuid") UUID mcUuid);

    /**
     * Writes the 1:1 link, or nothing if either side is taken.
     *
     * @return 1 if written, 0 otherwise
     */
    @SqlUpdate("""
            INSERT INTO account_link (discord_id, mc_uuid)
            VALUES (:discordId, :mcUuid)
            ON CONFLICT DO NOTHING
            """)
    int link(@Bind("discordId") DiscordId discordId, @Bind("mcUuid") UUID mcUuid);

    @SqlUpdate("DELETE FROM account_link WHERE discord_id = :discordId")
    int unlink(@Bind("discordId") DiscordId discordId);

    /**
     * Writes the Minecraft name last seen at login.
     *
     * @return the rows touched, 0 if {@code mcUuid} is not linked
     */
    @SqlUpdate("""
            UPDATE account_link
            SET mc_name = :name, mc_name_updated = now()
            WHERE mc_uuid = :mcUuid
            """)
    int setMinecraftName(@Bind("mcUuid") java.util.UUID mcUuid, @Bind("name") String name);

    @SqlQuery("SELECT mc_name, mc_name_updated FROM account_link WHERE discord_id = :discordId")
    @RegisterRowMapper(MinecraftProfileMapper.class)
    Optional<MinecraftProfile> minecraftProfile(@Bind("discordId") DiscordId discordId);

    /**
     * Appends a grant starting at {@code max(now(), smp_start, current valid_until)} on PostgreSQL's clock.
     * Periods are never summed. A day is exactly 24 hours in any session time zone.
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
            @Bind("discordId") DiscordId discordId,
            @Bind("days") int days,
            @Bind("source") String source,
            @Bind("paymentRequestId") @Nullable UUID paymentRequestId);

    /**
     * Revokes the whole remaining run of access, which keeps the live grants contiguous.
     *
     * @return the grants revoked
     */
    @SqlUpdate("""
            UPDATE access_grant
            SET revoked = now()
            WHERE discord_id = :discordId
              AND revoked IS NULL
              AND valid_until > now()
            """)
    int revokeAccess(@Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT id, discord_id, valid_from, valid_until, source, payment_request_id, revoked, created
            FROM access_grant
            WHERE discord_id = :discordId
            ORDER BY valid_from DESC, created DESC
            """)
    @RegisterRowMapper(AccessGrantMapper.class)
    java.util.List<AccessGrant> grantsOf(@Bind("discordId") DiscordId discordId);

    /** Returns the whole login state of the proxy in one statement. An unlinked account is a row of nulls. */
    @SqlQuery("""
            SELECT cast(:mcUuid AS uuid)                                    AS mc_uuid,
                   link.discord_id,
                   usr.member_state,
                   usr.locale,
                   usr.donor,
                   usr.admin,
                   usr.pack_exempt_at IS NOT NULL                           AS pack_exempt,
                   coalesce(playtime.seconds, 0)                            AS playtime_seconds,
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
                     LEFT JOIN player_playtime playtime ON playtime.discord_id = link.discord_id
            """)
    @RegisterRowMapper(AccessStateMapper.class)
    Optional<AccessState> accessState(@Bind("mcUuid") UUID mcUuid);

    @SqlQuery("""
            SELECT link.mc_uuid, link.discord_id, link.mc_name, usr.locale, usr.time_zone, usr.admin, usr.donor,
                   coalesce(smp.aura, 0) AS aura, coalesce(playtime.seconds, 0) AS playtime_seconds
            FROM account_link link
                     JOIN discord_user usr ON usr.discord_id = link.discord_id
                     LEFT JOIN player_playtime playtime ON playtime.discord_id = link.discord_id
                     LEFT JOIN smp_player smp ON smp.discord_id = link.discord_id
            WHERE link.mc_uuid = ANY(:mcUuids)
            """)
    @RegisterRowMapper(PlayerIdentityMapper.class)
    List<PlayerIdentity> identities(@Bind("mcUuids") UUID[] mcUuids);

    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId AND locale IS NOT NULL")
    Optional<String> languageOf(@Bind("discordId") DiscordId discordId);

    /**
     * Issues a code for one Minecraft account or returns the live one.
     * A collision with the code of another account violates the primary key, and the caller retries.
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

    @SqlQuery("SELECT mc_uuid FROM link_code WHERE code = :code AND expires > now()")
    Optional<UUID> mcUuidForActiveCode(@Bind("code") String code);

    @SqlUpdate("DELETE FROM link_code WHERE code = :code")
    int deleteLinkCode(@Bind("code") String code);
}
