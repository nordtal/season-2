package eu.nordtal.s2.smp.db;

import eu.nordtal.s2.smp.milestone.StoredProgress;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the SMP reads and writes, as one JDBI SqlObject.
 *
 * <p><b>Never called from the main thread.</b> A Paper server blocked on a database round trip has
 * stopped ticking. Every caller hops to an async task first and comes back to the main thread only
 * to touch the world.
 *
 * <p>The keys are {@code discord_id}, never the Minecraft UUID: the UUID reaches these tables
 * through {@code account_link}, and storing it twice would create a second answer to "whose account
 * is this".
 */
public interface SmpDao {

    // ---------------------------------------------------------------- identity

    @SqlQuery("SELECT discord_id FROM account_link WHERE mc_uuid = :mcUuid")
    Optional<String> discordIdOf(@Bind("mcUuid") UUID mcUuid);

    @SqlQuery("SELECT mc_uuid FROM account_link WHERE discord_id = :discordId")
    Optional<UUID> mcUuidOf(@Bind("discordId") String discordId);

    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    Optional<String> localeOf(@Bind("discordId") String discordId);

    /**
     * Everything the player composition is drawn from, in one round trip.
     *
     * <p>LEFT JOINs on purpose: somebody who has never earned aura has no {@code smp_player} row and
     * somebody the proxy has never counted has no {@code player_playtime} row. Neither is an error,
     * and an INNER JOIN would quietly make a new player invisible for their first session.
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

    /**
     * Whether this account holds the Discord admin flag.
     *
     * <p>Mirrored into {@code discord_user} by the bot and only read here. There is no second
     * admin list anywhere.
     */
    @SqlQuery("""
            SELECT usr.admin
            FROM account_link link
                     JOIN discord_user usr ON usr.discord_id = link.discord_id
            WHERE link.mc_uuid = :mcUuid
            """)
    Optional<Boolean> isAdmin(@Bind("mcUuid") UUID mcUuid);

    // ---------------------------------------------------------------- milestones

    /**
     * The keys of every milestone that is finished.
     *
     * <p>What each of them <em>unlocked</em> is not stored: that is the track's business and lives
     * in {@code milestones.yml}. The database holds progress, the file holds definition, and a key
     * here that the file no longer declares is exactly what {@code TrackValidation} exists to
     * catch.
     */
    // 'UNLOCKED' is the only spelling V6's CHECK and MilestoneState accept; anything else silently
    // matches no row. MilestoneStateIntegrationTest drives this against the real constraint.
    @SqlQuery("SELECT key FROM smp_milestone WHERE state = 'UNLOCKED' ORDER BY key")
    List<String> completedMilestoneKeys();

    /**
     * Every milestone row, for {@code TrackValidation}.
     *
     * <p>It reads everything because a rename is only visible against the whole set: the question
     * is which rows the file would orphan. A season's worth of rows, read once per reload, off the
     * main thread.</p>
     */
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
     * <p>Feeds both HUD line 1 and the objective board, which is why it returns the rows rather than
     * a ratio: the board names each objective, the HUD averages them.
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

    // ---------------------------------------------------------------- objective progress

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
    Optional<ObjectiveRow> objective(@Bind("milestoneKey") String milestoneKey,
                                     @Bind("objectiveKey") String objectiveKey);

    /**
     * Adds to an objective's collected amount.
     *
     * <p>{@code amount = amount + :delta} in SQL rather than read-modify-write in Java, so two
     * players handing in at the same moment cannot lose one of the deliveries. Deliberately not
     * clamped to the target: over-collection is real, and clamping would throw away somebody's
     * items.
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
    void addContribution(@Bind("objectiveId") UUID objectiveId, @Bind("discordId") String discordId,
                         @Bind("amount") long amount);

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
     * <p>A <b>left</b> join, so an objective this player has never touched comes back at zero: the
     * menu draws one line per objective, and a missing row would silently shorten the list.</p>
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
    List<OwnContributionRow> ownContributions(@Bind("milestoneKey") String milestoneKey,
                                              @Bind("discordId") String discordId);

    /**
     * Marks an objective finished, once.
     *
     * <p>The {@code completed IS NULL} guard is what makes the payout happen exactly once: two
     * deliveries landing in the same instant both see an incomplete objective, and only the update
     * that changes a row goes on to pay anybody.
     */
    @SqlUpdate("UPDATE smp_objective SET completed = now() WHERE id = :id AND completed IS NULL")
    int completeObjective(@Bind("id") UUID id);

    // ---------------------------------------------------------------- milestone transitions

    /**
     * Finishes a milestone and tells the rest of the network in the same statement.
     *
     * <p>The row and the {@code pg_notify} are one statement, so an announcement can never go out
     * for an unlock the database does not hold. The bot's {@code LISTEN nordtal_smp} is the whole
     * transport.
     *
     * <p>Returns empty when the milestone was already complete, which is what makes this safe to
     * call from two places at once.
     */
    @SqlQuery("""
            UPDATE smp_milestone
            SET state = 'UNLOCKED', unlocked = now()
            WHERE key = :key AND state <> 'UNLOCKED'
            RETURNING key, pg_notify('nordtal_smp', 'milestone:' || key) AS notified
            """)
    Optional<String> completeMilestone(@Bind("key") String key);

    /**
     * Makes sure the objective the file declares has a row, and that the row's target is the
     * file's.
     *
     * <p>The target is updated on conflict because lowering it is the first escape hatch and
     * {@code TrackValidation} has already decided whether the file may replace the running track.
     * {@code amount} and {@code completed} are never touched here.</p>
     */
    @SqlUpdate("""
            INSERT INTO smp_objective (milestone_key, key, type, target)
            VALUES (:milestoneKey, :key, :type, :target)
            ON CONFLICT (milestone_key, key) DO UPDATE SET target = EXCLUDED.target, type = EXCLUDED.type
            """)
    void ensureObjective(@Bind("milestoneKey") String milestoneKey, @Bind("key") String key,
                         @Bind("type") String type, @Bind("target") long target);

    @SqlUpdate("UPDATE smp_milestone SET state = 'ACTIVE' WHERE key = :key AND state = 'LOCKED'")
    int activateMilestone(@Bind("key") String key);

    // ---------------------------------------------------------------- aura

    /**
     * Books an aura change and its audit row.
     *
     * <p>Two statements that must not come apart, which is why every caller goes through this one
     * method: {@code smp_player.aura} is the balance and {@code smp_aura_event} is why it is what it
     * is.
     */
    @Transaction
    default void addAura(final String discordId, final int delta, final String reason, final String ref) {
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
    void recordAuraEvent(@Bind("discordId") String discordId, @Bind("delta") int delta,
                         @Bind("reason") String reason, @Bind("ref") String ref);

    /**
     * Writes a milestone row if the track declares one this database has never seen.
     *
     * <p>Idempotent on purpose: the track is reloadable while players are online, and a milestone
     * appended to the file mid-season has to appear without anybody running SQL by hand.
     */
    @SqlUpdate("""
            INSERT INTO smp_milestone (key, state)
            VALUES (:key, :state)
            ON CONFLICT (key) DO NOTHING
            """)
    void ensureMilestone(@Bind("key") String key, @Bind("state") String state);

    // ---------------------------------------------------------------- points of interest

    /**
     * Every POI, in the order they were created.
     *
     * <p>Public and unlimited: anyone may create one, everyone sees all of them, and admins can
     * delete any. There is no ownership beyond the name of whoever put it there.
     */
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
    void createPoi(@Bind("name") String name, @Bind("world") String world, @Bind("x") int x,
                   @Bind("y") int y, @Bind("z") int z, @Bind("createdBy") String createdBy);

    @SqlUpdate("DELETE FROM smp_poi WHERE id = :id")
    int deletePoi(@Bind("id") UUID id);

    /**
     * Drops every POI in a world.
     *
     * <p>Called for the farm world at every daily reset: an arrow pointing confidently at terrain
     * that no longer exists is worse than none.
     */
    @SqlUpdate("DELETE FROM smp_poi WHERE world = :world")
    int deletePoisIn(@Bind("world") String world);

    // ---------------------------------------------------------------- last death

    /**
     * Remembers where somebody died, for {@code /navigate}'s built-in target.
     *
     * <p>Upserts, because {@code smp_player} has no row until the player does something that needs
     * one - and dying is very often the first such thing.
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
    void rememberDeath(@Bind("discordId") String discordId, @Bind("world") String world,
                       @Bind("x") int x, @Bind("y") int y, @Bind("z") int z);

    @SqlQuery("""
            SELECT last_death_world AS world, last_death_x AS x, last_death_y AS y, last_death_z AS z
            FROM smp_player
            WHERE discord_id = :discordId AND last_death_world IS NOT NULL
            """)
    @RegisterConstructorMapper(PlaceRow.class)
    Optional<PlaceRow> lastDeathOf(@Bind("discordId") String discordId);

    // ---------------------------------------------------------------- the aura leaderboard

    /**
     * The highest aura, most first.
     *
     * <p>Joined through {@code account_link} because the board shows Minecraft players and the table
     * is keyed by Discord account. Somebody with an {@code smp_player} row but no link is left out:
     * they cannot be on a Minecraft server to be shown on a board in one.
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
     * Where an amount of aura places somebody, and how many people it is out of.
     *
     * <p>One statement, because two reads would describe two instants and let the asker's place
     * disagree with the list printed under it.
     *
     * <p>Joined through {@code account_link}, the same population {@link #topAura} draws from, so
     * "number 4 of 37" cannot sit above a list built from thirty-six people. Ties share a place -
     * the count is of everybody with <em>strictly</em> more.
     *
     * <p>The id is passed in so that an asker with no {@code smp_player} row of their own is still
     * counted in the population; otherwise a fresh season prints "1 of 0".
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

    // ---------------------------------------------------------------- the start event's winner

    /**
     * The Discord id of the player who won the start event, if one has been decided.
     *
     * <p>The <em>earliest</em> decided game, not the newest: a later practice game must not move a
     * reward that has already been paid, and there is no way to take one back. {@code created}
     * rather than {@code ended} because it is {@code NOT NULL}.
     *
     * <p>The join makes this safe to call on every login: a game decided with no winner has
     * {@code winner_member_id IS NULL} and drops out rather than returning an unpayable row.</p>
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
     * Claims the winner's head start and books its aura, or answers that it is already gone.
     *
     * <p>The claim is the gate and it is one statement: the {@code WHERE} on the {@code DO UPDATE}
     * matches nothing for a row already carrying {@code true}, so a reconnect or a replayed join
     * answers {@code false} without writing anything.
     *
     * <p>The {@code INSERT} half matters: the winner has by definition played no SMP yet and so
     * normally has no {@code smp_player} row at all.
     *
     * <p>Aura is booked in the same transaction, because a claim that succeeded next to a payout
     * that did not is the one outcome nothing can repair.
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

    // ---------------------------------------------------------------- the season's opening moment

    /**
     * Takes this player's one welcome, if it is still there.
     *
     * <p>{@link #claimHeadStart}'s shape and for its reason: the flag is written before anything is
     * shown, so two racing sessions cannot both win. The {@code INSERT} half matters because this
     * runs on the join of a player who has earned nothing yet.
     *
     * <p>Deliberately not transactional: showing before recording would show the moment twice to
     * anybody whose server restarts mid-welcome. The cost is the one path that loses it - a player
     * who leaves in the tick after their own join - which is logged rather than repaired.
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

    // ---------------------------------------------------------------- graves

    @SqlUpdate("""
            INSERT INTO smp_grave (owner_id, world, x, y, z, contents, experience)
            VALUES (:ownerId, :world, :x, :y, :z, :contents, :experience)
            """)
    void createGrave(@Bind("ownerId") String ownerId, @Bind("world") String world,
                     @Bind("x") int x, @Bind("y") int y, @Bind("z") int z,
                     @Bind("contents") byte[] contents, @Bind("experience") int experience);

    /**
     * Every grave that still holds something.
     *
     * <p>Read at start so the displays can be put back: a grave outlives a restart, which is most of
     * what "the grave stands forever" means in practice.
     */
    @SqlQuery("""
            SELECT grave.id            AS id,
                   grave.owner_id      AS ownerId,
                   link.mc_uuid        AS ownerUuid,
                   grave.world         AS world,
                   grave.x             AS x,
                   grave.y             AS y,
                   grave.z             AS z,
                   grave.contents      AS contents,
                   grave.experience    AS experience
            FROM smp_grave grave
                     LEFT JOIN account_link link ON link.discord_id = grave.owner_id
            WHERE grave.looted IS NULL
            ORDER BY grave.created
            """)
    @RegisterConstructorMapper(GraveRow.class)
    List<GraveRow> openGraves();

    /**
     * Marks a grave emptied, once.
     *
     * <p>Anyone may open a grave, so two people can empty the same one in the same instant; the
     * {@code looted IS NULL} guard is what credits the experience exactly once.
     */
    @SqlQuery("""
            UPDATE smp_grave
            SET looted = now(), looted_by = :lootedBy
            WHERE id = :id AND looted IS NULL
            RETURNING id
            """)
    Optional<UUID> markGraveLooted(@Bind("id") UUID id, @Bind("lootedBy") String lootedBy);

    /**
     * Writes back what is left in a grave somebody only half emptied.
     *
     * <p><b>Without this a restart hands the contents out again</b>: the enable-time restore reads
     * the row, so a partial loot kept only in memory would be refilled while the items already taken
     * sat in the looter's inventory.
     *
     * <p>{@code looted IS NULL} for {@link #markGraveLooted}'s reason: a grave somebody else
     * finished must not be refilled by a late close.
     */
    @SqlUpdate("UPDATE smp_grave SET contents = :contents WHERE id = :id AND looted IS NULL")
    int updateGraveContents(@Bind("id") UUID id, @Bind("contents") byte[] contents);

    /**
     * Drops every grave in a world.
     *
     * <p>The farm world at its daily reset. Everything there is destroyed, graves included.
     */
    @SqlUpdate("DELETE FROM smp_grave WHERE world = :world")
    int deleteGravesIn(@Bind("world") String world);

    // ---------------------------------------------------------------- the wheel

    @SqlQuery("""
            SELECT granted AS granted, used AS used, last_free AS lastFree
            FROM smp_spin
            WHERE discord_id = :discordId
            """)
    @RegisterConstructorMapper(eu.nordtal.s2.smp.wheel.Spins.class)
    Optional<eu.nordtal.s2.smp.wheel.Spins> spinsOf(@Bind("discordId") String discordId);

    /**
     * Takes today's free spin, once.
     *
     * <p>The {@code last_free IS DISTINCT FROM :today} guard is what makes it once: two clicks in
     * the same second both see a free spin, and only the update that changes a row gets a prize.
     */
    @SqlQuery("""
            INSERT INTO smp_spin (discord_id, last_free)
            VALUES (:discordId, :today)
            ON CONFLICT (discord_id) DO UPDATE
                SET last_free = :today
                WHERE smp_spin.last_free IS DISTINCT FROM :today
            RETURNING discord_id
            """)
    Optional<String> takeFreeSpin(@Bind("discordId") String discordId,
                                  @Bind("today") java.time.LocalDate today);

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
     * <p>The spin is spent before the prize is drawn, so two paths end with a spent row and an
     * empty hand: a disconnect between the commit and the next tick, and a {@code wheel-prizes}
     * entry naming an item this server does not know.
     *
     * <p>{@code last_free = :today} makes this idempotent - a second call finds the row already
     * restored. {@code previous} is <b>null for a player's first ever free spin</b>; the
     * {@code CAST} is there so an untyped null date cannot become "could not determine data type of
     * parameter".
     */
    @SqlUpdate("""
            UPDATE smp_spin
            SET last_free = CAST(:previous AS date)
            WHERE discord_id = :discordId AND last_free = :today
            """)
    void restoreFreeSpin(@Bind("discordId") String discordId,
                         @Bind("previous") java.time.LocalDate previous,
                         @Bind("today") java.time.LocalDate today);

    /**
     * Puts back an earned spin that paid out nothing. See {@link #restoreFreeSpin}.
     *
     * <p>{@code used > 0} keeps {@code smp_spin_used_not_negative} satisfied, but unlike the free
     * one this is <b>not</b> idempotent: a second call hands back a second spin. It is safe only
     * because the two call sites are mutually exclusive and each runs at most once per spin.
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
