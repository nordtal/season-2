package eu.nordtal.s2.smp.db;

import eu.nordtal.s2.smp.milestone.StoredProgress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Everything the SMP reads and writes, as one JDBI SqlObject, never called from the main thread.
 *
 * Keyed by {@code discord_id}; the Minecraft UUID arrives through {@code account_link}.
 */
public interface SmpDao {

    @SqlQuery("SELECT discord_id FROM account_link WHERE mc_uuid = :mcUuid")
    Optional<String> discordIdOf(@Bind("mcUuid") UUID mcUuid);

    @SqlQuery("SELECT mc_uuid FROM account_link WHERE discord_id = :discordId")
    Optional<UUID> mcUuidOf(@Bind("discordId") String discordId);

    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    Optional<String> localeOf(@Bind("discordId") String discordId);

    /**
     * Everything the player composition is drawn from, in one round trip.
     *
     * LEFT JOINs, so a new player with no {@code smp_player} or {@code player_playtime} row is still found.
     */
    @SqlQuery("""
            SELECT usr.locale       AS locale,
                   usr.admin        AS admin,
                   usr.donor        AS donor,
                   player.aura      AS aura,
                   playtime.seconds AS playtimeSeconds
            FROM account_link link
                     JOIN discord_user usr ON usr.discord_id = link.discord_id
                     LEFT JOIN smp_player player ON player.discord_id = link.discord_id
                     LEFT JOIN player_playtime playtime ON playtime.discord_id = link.discord_id
            WHERE link.mc_uuid = :mcUuid
            """)
    @RegisterConstructorMapper(IdentityRow.class)
    Optional<IdentityRow> identityOf(@Bind("mcUuid") UUID mcUuid);

    /** Whether this account holds the Discord admin flag, mirrored into {@code discord_user} by the bot. */
    @SqlQuery("""
            SELECT usr.admin
            FROM account_link link
                     JOIN discord_user usr ON usr.discord_id = link.discord_id
            WHERE link.mc_uuid = :mcUuid
            """)
    Optional<Boolean> isAdmin(@Bind("mcUuid") UUID mcUuid);

    /**
     * The keys of every milestone that is finished.
     *
     * What each unlocked lives in {@code milestones.yml}, checked against these by {@code TrackValidation}.
     */
    @SqlQuery("SELECT key FROM smp_milestone WHERE state = 'UNLOCKED' ORDER BY key")
    List<String> completedMilestoneKeys();

    /** Every milestone row, for {@code TrackValidation}, which needs the whole set to see a rename. */
    @SqlQuery("SELECT key, state FROM smp_milestone ORDER BY key")
    @RegisterConstructorMapper(StoredProgress.StoredMilestone.class)
    List<StoredProgress.StoredMilestone> storedMilestones();

    /** Every objective row, with the type and target it was created under. See {@link #storedMilestones}. */
    @SqlQuery("""
            SELECT milestone_key              AS milestoneKey,
                   key                        AS key,
                   type                       AS type,
                   amount                     AS amount,
                   target                     AS target,
                   (completed IS NOT NULL)    AS completed
            FROM smp_objective
            ORDER BY milestone_key, key
            """)
    @RegisterConstructorMapper(StoredProgress.StoredObjective.class)
    List<StoredProgress.StoredObjective> storedObjectives();

    @SqlQuery("SELECT key FROM smp_milestone WHERE state = 'ACTIVE' LIMIT 1")
    Optional<String> activeMilestoneKey();

    @SqlQuery("SELECT state FROM smp_milestone WHERE key = :key")
    Optional<String> milestoneState(@Bind("key") String key);

    /**
     * Every objective of one milestone, with its progress.
     *
     * Rows rather than a ratio: the board names each objective, the HUD averages them.
     */
    @SqlQuery("""
            SELECT obj.id                        AS id,
                   obj.key                       AS key,
                   obj.amount                    AS amount,
                   obj.target                    AS target,
                   (obj.completed IS NOT NULL)   AS completed
            FROM smp_objective obj
            WHERE obj.milestone_key = :milestoneKey
            ORDER BY obj.key
            """)
    @RegisterConstructorMapper(ObjectiveRow.class)
    List<ObjectiveRow> objectivesOf(@Bind("milestoneKey") String milestoneKey);

    @SqlQuery("""
            SELECT obj.id                      AS id,
                   obj.key                     AS key,
                   obj.amount                  AS amount,
                   obj.target                  AS target,
                   (obj.completed IS NOT NULL) AS completed
            FROM smp_objective obj
            WHERE obj.milestone_key = :milestoneKey AND obj.key = :objectiveKey
            """)
    @RegisterConstructorMapper(ObjectiveRow.class)
    Optional<ObjectiveRow> objective(
            @Bind("milestoneKey") String milestoneKey, @Bind("objectiveKey") String objectiveKey);

    /**
     * Adds to an objective's collected amount, atomically in SQL.
     *
     * Not clamped to the target: over-collection is real, and clamping would throw away somebody's items.
     */
    @SqlUpdate("UPDATE smp_objective SET amount = amount + :delta WHERE id = :id")
    int addObjectiveProgress(@Bind("id") UUID id, @Bind("delta") long delta);

    @SqlUpdate("""
            INSERT INTO smp_contribution (objective_id, discord_id, amount)
            VALUES (:objectiveId, :discordId, :amount)
            ON CONFLICT (objective_id, discord_id) DO UPDATE
                SET amount  = smp_contribution.amount + excluded.amount,
                    updated = now()
            """)
    void addContribution(
            @Bind("objectiveId") UUID objectiveId, @Bind("discordId") String discordId, @Bind("amount") long amount);

    @SqlQuery("""
            SELECT discord_id AS discordId, amount AS amount
            FROM smp_contribution
            WHERE objective_id = :objectiveId AND amount > 0
            """)
    @RegisterConstructorMapper(ContributionRow.class)
    List<ContributionRow> contributionsOf(@Bind("objectiveId") UUID objectiveId);

    /**
     * What one player has put into each objective of one milestone.
     *
     * A left join, so an untouched objective comes back at zero instead of shortening the menu's list.
     */
    @SqlQuery("""
            SELECT obj.key               AS key,
                   coalesce(con.amount, 0) AS mine,
                   obj.target            AS target
            FROM smp_objective obj
            LEFT JOIN smp_contribution con
                   ON con.objective_id = obj.id AND con.discord_id = :discordId
            WHERE obj.milestone_key = :milestoneKey
            """)
    @RegisterConstructorMapper(OwnContributionRow.class)
    List<OwnContributionRow> ownContributions(
            @Bind("milestoneKey") String milestoneKey, @Bind("discordId") String discordId);

    /**
     * Marks an objective finished, once.
     *
     * The {@code completed IS NULL} guard lets only one of two simultaneous deliveries go on to pay anybody.
     */
    @SqlUpdate("UPDATE smp_objective SET completed = now() WHERE id = :id AND completed IS NULL")
    int completeObjective(@Bind("id") UUID id);

    /**
     * Finishes a milestone and sends {@code pg_notify} in the same statement.
     *
     * Returns empty unless the milestone was the active one, so two callers at once are safe and no unlock skips ahead.
     */
    @SqlQuery("""
            UPDATE smp_milestone
            SET state = 'UNLOCKED', unlocked = now()
            WHERE key = :key AND state = 'ACTIVE'
            RETURNING key, pg_notify('nordtal_smp', 'milestone:' || key) AS notified
            """)
    Optional<String> completeMilestone(@Bind("key") String key);

    /**
     * Makes sure the objective the file declares has a row, with the file's target.
     *
     * Never touches {@code amount} or {@code completed}; {@code TrackValidation} has already approved the target.
     */
    @SqlUpdate("""
            INSERT INTO smp_objective (milestone_key, key, type, target)
            VALUES (:milestoneKey, :key, :type, :target)
            ON CONFLICT (milestone_key, key) DO UPDATE SET target = EXCLUDED.target, type = EXCLUDED.type
            """)
    void ensureObjective(
            @Bind("milestoneKey") String milestoneKey,
            @Bind("key") String key,
            @Bind("type") String type,
            @Bind("target") long target);

    @SqlUpdate("UPDATE smp_milestone SET state = 'ACTIVE' WHERE key = :key AND state = 'LOCKED'")
    int activateMilestone(@Bind("key") String key);

    /** Activates {@code key} only while nothing is active and {@code completed} milestones are still unlocked. */
    @SqlUpdate("""
            UPDATE smp_milestone SET state = 'ACTIVE'
            WHERE key = :key AND state = 'LOCKED'
              AND NOT EXISTS (SELECT 1 FROM smp_milestone WHERE state = 'ACTIVE')
              AND (SELECT count(*) FROM smp_milestone WHERE state = 'UNLOCKED') = :completed
            """)
    int activateAfter(@Bind("key") String key, @Bind("completed") int completed);

    /** Books an aura change and its audit row together, the balance and the reason for it. */
    @Transaction
    default void addAura(final String discordId, final int delta, final String reason, final @Nullable String ref) {
        bumpAura(discordId, delta);
        recordAuraEvent(discordId, delta, reason, ref);
    }

    @SqlUpdate("""
            INSERT INTO smp_player (discord_id, aura)
            VALUES (:discordId, :delta)
            ON CONFLICT (discord_id) DO UPDATE
                SET aura = smp_player.aura + excluded.aura, updated = now()
            """)
    void bumpAura(@Bind("discordId") String discordId, @Bind("delta") int delta);

    @SqlUpdate("""
            INSERT INTO smp_aura_event (discord_id, delta, reason, ref)
            VALUES (:discordId, :delta, :reason, :ref)
            """)
    void recordAuraEvent(
            @Bind("discordId") String discordId,
            @Bind("delta") int delta,
            @Bind("reason") String reason,
            @Bind("ref") @Nullable String ref);

    /**
     * Writes a milestone row this database has never seen, idempotently, so an appended milestone appears on reload.
     */
    @SqlUpdate("""
            INSERT INTO smp_milestone (key, state)
            VALUES (:key, :state)
            ON CONFLICT (key) DO NOTHING
            """)
    void ensureMilestone(@Bind("key") String key, @Bind("state") String state);

    /** Every POI, in the order they were created. */
    @SqlQuery("""
            SELECT poi.id         AS id,
                   poi.name       AS name,
                   poi.world      AS world,
                   poi.x          AS x,
                   poi.y          AS y,
                   poi.z          AS z,
                   poi.created_by AS createdBy
            FROM smp_poi poi
            ORDER BY poi.created
            """)
    @RegisterConstructorMapper(PoiRow.class)
    List<PoiRow> allPois();

    @SqlUpdate("""
            INSERT INTO smp_poi (name, world, x, y, z, created_by)
            VALUES (:name, :world, :x, :y, :z, :createdBy)
            """)
    void createPoi(
            @Bind("name") String name,
            @Bind("world") String world,
            @Bind("x") int x,
            @Bind("y") int y,
            @Bind("z") int z,
            @Bind("createdBy") String createdBy);

    @SqlUpdate("DELETE FROM smp_poi WHERE id = :id")
    int deletePoi(@Bind("id") UUID id);

    /**
     * Remembers where somebody died, for {@code /navigate}'s built-in target.
     *
     * Upserts, because dying is often the first thing that needs an {@code smp_player} row.
     */
    @SqlUpdate("""
            INSERT INTO smp_player (discord_id, last_death_world, last_death_x, last_death_y, last_death_z)
            VALUES (:discordId, :world, :x, :y, :z)
            ON CONFLICT (discord_id) DO UPDATE
                SET last_death_world = excluded.last_death_world,
                    last_death_x     = excluded.last_death_x,
                    last_death_y     = excluded.last_death_y,
                    last_death_z     = excluded.last_death_z,
                    updated          = now()
            """)
    void rememberDeath(
            @Bind("discordId") String discordId,
            @Bind("world") String world,
            @Bind("x") int x,
            @Bind("y") int y,
            @Bind("z") int z);

    @SqlQuery("""
            SELECT last_death_world AS world, last_death_x AS x, last_death_y AS y, last_death_z AS z
            FROM smp_player
            WHERE discord_id = :discordId AND last_death_world IS NOT NULL
            """)
    @RegisterConstructorMapper(PlaceRow.class)
    Optional<PlaceRow> lastDeathOf(@Bind("discordId") String discordId);

    /**
     * The highest aura, most first.
     *
     * Only players with an {@code account_link} are listed, since the board shows Minecraft players.
     */
    @SqlQuery("""
            SELECT link.mc_uuid AS mcUuid,
                   player.aura  AS aura
            FROM smp_player player
                     JOIN account_link link ON link.discord_id = player.discord_id
            ORDER BY player.aura DESC, link.mc_uuid
            LIMIT :limit
            """)
    @RegisterConstructorMapper(AuraRow.class)
    List<AuraRow> topAura(@Bind("limit") int limit);

    @SqlQuery("SELECT aura FROM smp_player WHERE discord_id = :discordId")
    Optional<Integer> auraOf(@Bind("discordId") String discordId);

    /**
     * Where an amount of aura places somebody, and out of how many, in one statement.
     *
     * The same population as {@link #topAura}, and the asker always counts, so a fresh season never prints "1 of 0".
     */
    @SqlQuery("""
            SELECT count(*) FILTER (WHERE player.aura > :aura) + 1 AS place,
                   count(*) + CASE WHEN coalesce(bool_or(player.discord_id = :discordId), false)
                                   THEN 0 ELSE 1 END                AS total
            FROM smp_player player
                     JOIN account_link link ON link.discord_id = player.discord_id
            """)
    @RegisterConstructorMapper(AuraPlace.class)
    AuraPlace auraPlace(@Bind("aura") int aura, @Bind("discordId") String discordId);

    /**
     * The Discord id of the player who won the start event, if one has been decided.
     *
     * The earliest decided game, so a later practice game cannot move a reward already paid.
     */
    @SqlQuery("""
            SELECT member.discord_id
            FROM hg_game game
                     JOIN hg_member member ON member.id = game.winner_member_id
            WHERE game.state = 'DECIDED'
            ORDER BY game.created
            LIMIT 1
            """)
    Optional<String> startEventWinner();

    /**
     * Claims the winner's head start and books its aura in one transaction, or answers that it is already gone.
     *
     * @return whether this call is the one that granted it
     */
    @Transaction
    default boolean grantHeadStart(final String discordId, final int aura, final String reason) {
        if (claimHeadStart(discordId) == 0) {
            return false;
        }
        if (aura != 0) {
            addAura(discordId, aura, reason, null);
        }
        return true;
    }

    @SqlUpdate("""
            INSERT INTO smp_player (discord_id, hg_winner_reward_granted)
            VALUES (:discordId, true)
            ON CONFLICT (discord_id) DO UPDATE
                SET hg_winner_reward_granted = true, updated = now()
                WHERE NOT smp_player.hg_winner_reward_granted
            """)
    int claimHeadStart(@Bind("discordId") String discordId);

    /**
     * Takes this player's one welcome, claimed before anything is shown so racing sessions cannot both win.
     *
     * @return whether this call is the one that took it
     */
    default boolean claimWelcome(final String discordId) {
        return claimWelcomeRow(discordId) > 0;
    }

    @SqlUpdate("""
            INSERT INTO smp_player (discord_id, welcome_shown)
            VALUES (:discordId, true)
            ON CONFLICT (discord_id) DO UPDATE
                SET welcome_shown = true, updated = now()
                WHERE NOT smp_player.welcome_shown
            """)
    int claimWelcomeRow(@Bind("discordId") String discordId);

    @SqlUpdate("""
            INSERT INTO smp_grave (owner_id, world, x, y, z, contents, experience)
            VALUES (:ownerId, :world, :x, :y, :z, :contents, :experience)
            """)
    void createGrave(
            @Bind("ownerId") String ownerId,
            @Bind("world") String world,
            @Bind("x") int x,
            @Bind("y") int y,
            @Bind("z") int z,
            @Bind("contents") byte[] contents,
            @Bind("experience") int experience);

    /** Every grave that still holds something, read at start so the displays can be put back. */
    @SqlQuery("""
            SELECT grave.id            AS id,
                   grave.owner_id      AS ownerId,
                   link.mc_uuid        AS ownerUuid,
                   grave.world         AS world,
                   grave.x             AS x,
                   grave.y             AS y,
                   grave.z             AS z,
                   grave.contents      AS contents,
                   grave.experience    AS experience,
                   grave.created       AS created
            FROM smp_grave grave
                     LEFT JOIN account_link link ON link.discord_id = grave.owner_id
            WHERE grave.looted IS NULL
            ORDER BY grave.created
            """)
    @RegisterRowMapper(GraveRowMapper.class)
    List<GraveRow> openGraves();

    /**
     * Marks a grave emptied, once.
     *
     * The {@code looted IS NULL} guard credits the experience exactly once when two people empty it together.
     */
    @SqlQuery("""
            UPDATE smp_grave
            SET looted = now(), looted_by = :lootedBy
            WHERE id = :id AND looted IS NULL
            RETURNING id
            """)
    Optional<UUID> markGraveLooted(@Bind("id") UUID id, @Bind("lootedBy") @Nullable String lootedBy);

    /**
     * Writes back what is left in a half emptied grave, or a restart would hand the contents out again.
     *
     * {@code looted IS NULL}, so a late close cannot refill a grave somebody else finished.
     */
    @SqlUpdate("UPDATE smp_grave SET contents = :contents WHERE id = :id AND looted IS NULL")
    int updateGraveContents(@Bind("id") UUID id, @Bind("contents") byte[] contents);

    /**
     * Deletes every grave older than {@code hours}, by its own {@code created}, and says which ones went.
     *
     * @param hours how long a grave may stand; positive, since 0 means this is never called
     */
    @SqlQuery("""
            DELETE FROM smp_grave
            WHERE looted IS NULL
              AND created < now() - make_interval(hours => :hours)
            RETURNING id, world, x, y, z
            """)
    @RegisterConstructorMapper(ExpiredGrave.class)
    List<ExpiredGrave> expireGravesOlderThan(@Bind("hours") int hours);

    @SqlQuery("""
            SELECT granted AS granted, used AS used, last_free AS lastFree
            FROM smp_spin
            WHERE discord_id = :discordId
            """)
    @RegisterConstructorMapper(Spins.class)
    Optional<Spins> spinsOf(@Bind("discordId") String discordId);

    /**
     * Takes today's free spin, once.
     *
     * The {@code last_free IS DISTINCT FROM :today} guard gives only one of two simultaneous clicks a prize.
     */
    @SqlQuery("""
            INSERT INTO smp_spin (discord_id, last_free)
            VALUES (:discordId, :today)
            ON CONFLICT (discord_id) DO UPDATE
                SET last_free = :today
                WHERE smp_spin.last_free IS DISTINCT FROM :today
            RETURNING discord_id
            """)
    Optional<String> takeFreeSpin(@Bind("discordId") String discordId, @Bind("today") java.time.LocalDate today);

    /** Spends one earned spin, and only if there is one to spend. */
    @SqlQuery("""
            UPDATE smp_spin
            SET used = used + 1
            WHERE discord_id = :discordId AND used < granted
            RETURNING discord_id
            """)
    Optional<String> takeEarnedSpin(@Bind("discordId") String discordId);

    /**
     * Puts back a free spin that was taken and paid out nothing.
     *
     * Idempotent; {@code previous} is null for a player's first ever free spin, hence the {@code CAST}.
     */
    @SqlUpdate("""
            UPDATE smp_spin
            SET last_free = CAST(:previous AS date)
            WHERE discord_id = :discordId AND last_free = :today
            """)
    void restoreFreeSpin(
            @Bind("discordId") String discordId,
            @Bind("previous") java.time.@Nullable LocalDate previous,
            @Bind("today") java.time.LocalDate today);

    /**
     * Puts back an earned spin that paid out nothing.
     *
     * Not idempotent: a second call hands back a second spin, so each call site runs at most once per spin.
     */
    @SqlUpdate("UPDATE smp_spin SET used = used - 1 WHERE discord_id = :discordId AND used > 0")
    void restoreEarnedSpin(@Bind("discordId") String discordId);

    @SqlUpdate("""
            INSERT INTO smp_spin (discord_id, granted)
            VALUES (:discordId, :count)
            ON CONFLICT (discord_id) DO UPDATE
                SET granted = smp_spin.granted + excluded.granted
            """)
    void grantSpins(@Bind("discordId") String discordId, @Bind("count") int count);
}
