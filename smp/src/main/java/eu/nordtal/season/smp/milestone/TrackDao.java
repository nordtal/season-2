package eu.nordtal.season.smp.milestone;

import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifies;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The milestone track's rows: which milestone stands where, and each objective's stored progress.
 *
 * A JDBI SqlObject, never called from the main thread; the credits that move an objective are {@code ProgressDao}'s.
 */
@Notifies(Channel.SMP)
public interface TrackDao {

    /**
     * The keys of every milestone that is finished.
     *
     * What each unlocked lives in the {@code milestones} group, checked against these by {@code TrackValidation}.
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
     * Finishes a milestone and sends {@code pg_notify} in the same statement.
     *
     * Returns empty unless the milestone was the active one, so two callers at once are safe and no unlock skips ahead.
     */
    @SqlQuery("""
            UPDATE smp_milestone
            SET state = 'UNLOCKED', unlocked = now()
            WHERE key = :key AND state = 'ACTIVE'
            RETURNING key, pg_notify(:channel, 'milestone:' || key) AS notified
            """)
    Optional<String> completeMilestone(@Bind("key") String key);

    /**
     * Writes a milestone row this database has never seen, idempotently, so an appended milestone appears on reload.
     */
    @SqlUpdate("""
            INSERT INTO smp_milestone (key, state)
            VALUES (:key, :state)
            ON CONFLICT (key) DO NOTHING
            """)
    void ensureMilestone(@Bind("key") String key, @Bind("state") String state);

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

    /**
     * Starts the track over once for every fresh start the phase stamped since the last one.
     * Milestones locked, objectives emptied, contributions gone, the marker set, in one statement; aura stays.
     *
     * @return 1 when this call started the track over, 0 when there was nothing to do
     */
    @SqlQuery("""
            WITH due AS (
                SELECT phase.fresh_start
                FROM season_phase phase
                         LEFT JOIN smp_reset reset ON reset.id
                WHERE phase.id
                  AND phase.fresh_start IS NOT NULL
                  AND (reset.applied IS NULL OR reset.applied < phase.fresh_start)
            ),
                 marked AS (
                     INSERT INTO smp_reset (id, applied)
                     SELECT true, fresh_start FROM due
                     ON CONFLICT (id) DO UPDATE SET applied = EXCLUDED.applied
                         WHERE smp_reset.applied < EXCLUDED.applied
                     RETURNING applied
                 ),
                 cleared_contributions AS (
                     DELETE FROM smp_contribution WHERE EXISTS (SELECT 1 FROM marked)
                 ),
                 cleared_objectives AS (
                     UPDATE smp_objective SET amount = 0, completed = NULL WHERE EXISTS (SELECT 1 FROM marked)
                 ),
                 locked_milestones AS (
                     UPDATE smp_milestone SET state = 'LOCKED', unlocked = NULL WHERE EXISTS (SELECT 1 FROM marked)
                 )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM marked) AS notified
            """)
    int startOverIfDue();

    @SqlQuery("""
            WITH updated AS (
                UPDATE smp_milestone SET state = 'ACTIVE' WHERE key = :key AND state = 'LOCKED' RETURNING key
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM updated) AS notified
            """)
    int activateMilestone(@Bind("key") String key);

    /** Activates {@code key} only while nothing is active and {@code completed} milestones are still unlocked. */
    @SqlQuery("""
            WITH updated AS (
                UPDATE smp_milestone SET state = 'ACTIVE'
                WHERE key = :key AND state = 'LOCKED'
                  AND NOT EXISTS (SELECT 1 FROM smp_milestone WHERE state = 'ACTIVE')
                  AND (SELECT count(*) FROM smp_milestone WHERE state = 'UNLOCKED') = :completed
                RETURNING key
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM updated) AS notified
            """)
    int activateAfter(@Bind("key") String key, @Bind("completed") int completed);
}
