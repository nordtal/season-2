package eu.nordtal.s2.smp.db;

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
 * <p><b>Never called from the main thread.</b> That rule was written into this repository on
 * 2026-09-01 after {@code /hg start} was found doing exactly that: a Paper server blocked on a
 * database round trip is a server that has stopped ticking, and a slow query becomes a visible
 * freeze for everybody. Every caller here hops to an async task first and comes back to the main
 * thread only to touch the world.
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
     * <p>Mirrored into {@code discord_user} by the bot and only read here. There is no LuckPerms
     * anywhere in this repository and no second admin list (docs/smp.md#admins).
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
    @SqlQuery("SELECT key FROM smp_milestone WHERE state = 'COMPLETE' ORDER BY key")
    List<String> completedMilestoneKeys();

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
     * players handing in at the same moment cannot lose one of the two deliveries. There is no
     * clamp to the target here on purpose: over-collection is real - a target lowered by a reload is
     * one of the concept's escape hatches - and clamping would silently throw away somebody's items.
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
     * <p>The row and the {@code pg_notify} are one statement, exactly as the phase switch is: not
     * "remember to notify after updating", which is a rule somebody eventually forgets, but a thing
     * that cannot happen in one order. An announcement can therefore never go out for an unlock the
     * database does not hold, and the bot's {@code LISTEN nordtal_smp} is the whole transport.
     *
     * <p>Returns empty when the milestone was already complete, which is what makes this safe to
     * call from two places at once.
     */
    @SqlQuery("""
            UPDATE smp_milestone
            SET state = 'COMPLETE', unlocked = now()
            WHERE key = :key AND state <> 'COMPLETE'
            RETURNING key, pg_notify('nordtal_smp', 'milestone:' || key) AS notified
            """)
    Optional<String> completeMilestone(@Bind("key") String key);

    @SqlUpdate("UPDATE smp_milestone SET state = 'ACTIVE' WHERE key = :key AND state = 'LOCKED'")
    int activateMilestone(@Bind("key") String key);

    // ---------------------------------------------------------------- aura

    /**
     * Books an aura change and its audit row.
     *
     * <p>Two statements that must not come apart, which is why they are one method and why every
     * caller goes through it: {@code smp_player.aura} is the balance and {@code smp_aura_event} is
     * the reason it is what it is. A balance nobody can explain is a balance nobody trusts, and the
     * admin correction command exists precisely because somebody will need to explain one.
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
     * <p>Public and unlimited by design (docs/smp.md#navigate): anyone may create one, everyone sees
     * all of them, and admins can delete any. There is no per-player list because there is no
     * ownership beyond the name of whoever put it there.
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
     * <p>Called for the farm world at every daily reset. Nothing in the farm world survives - chests,
     * graves and POIs alike - and a POI pointing at terrain that no longer exists would be worse
     * than none, because the arrow would still be confident about it.
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
     * <p>Anyone may open a grave - no timer, no ownership lock - so two people can empty the same one
     * in the same instant. The {@code looted IS NULL} guard is what makes the experience credit
     * happen exactly once, and it is the same shape that guards an objective's payout.
     */
    @SqlQuery("""
            UPDATE smp_grave
            SET looted = now(), looted_by = :lootedBy
            WHERE id = :id AND looted IS NULL
            RETURNING id
            """)
    Optional<UUID> markGraveLooted(@Bind("id") UUID id, @Bind("lootedBy") String lootedBy);

    /**
     * Drops every grave in a world.
     *
     * <p>The farm world at its daily reset. That everything there is destroyed, graves included, is
     * intended and announced - it is the one real risk of going there - and it will be reported as a
     * bug anyway.
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
     * <p>The {@code last_free IS DISTINCT FROM :today} guard is what makes it once: two clicks in the
     * same second both see a free spin, and only the update that changes a row gets a prize. Same
     * shape as the objective payout and the grave loot, for the same reason.
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

    @SqlUpdate("""
            INSERT INTO smp_spin (discord_id, granted)
            VALUES (:discordId, :count)
            ON CONFLICT (discord_id) DO UPDATE
                SET granted = smp_spin.granted + excluded.granted
            """)
    void grantSpins(@Bind("discordId") String discordId, @Bind("count") int count);
}
