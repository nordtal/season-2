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
 * Reads and writes {@code smp_milestone} and {@code smp_objective}.
 * It is never called from the main thread.
 */
@Notifies(Channel.SMP)
public interface TrackDao {

    /** Returns the keys of the milestones in state {@code UNLOCKED}. */
    @SqlQuery("SELECT key FROM smp_milestone WHERE state = 'UNLOCKED' ORDER BY key")
    List<String> completedMilestoneKeys();

    @SqlQuery("SELECT key, state FROM smp_milestone ORDER BY key")
    @RegisterConstructorMapper(StoredProgress.StoredMilestone.class)
    List<StoredProgress.StoredMilestone> storedMilestones();

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
     * Sets the milestone to {@code UNLOCKED} and sends a notification in the same statement.
     *
     * @return the key, or empty when the milestone was not {@code ACTIVE}
     */
    @SqlQuery("""
            UPDATE smp_milestone
            SET state = 'UNLOCKED', unlocked = now()
            WHERE key = :key AND state = 'ACTIVE'
            RETURNING key, pg_notify(:channel, 'milestone:' || key) AS notified
            """)
    Optional<String> completeMilestone(@Bind("key") String key);

    /** Inserts the milestone row when it does not exist. */
    @SqlUpdate("""
            INSERT INTO smp_milestone (key, state)
            VALUES (:key, :state)
            ON CONFLICT (key) DO NOTHING
            """)
    void ensureMilestone(@Bind("key") String key, @Bind("state") String state);

    /** Inserts the objective row, or sets {@code target} and {@code type} of the existing row. */
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
     * Resets the track when {@code season_phase.fresh_start} is set and {@code smp_reset.applied} is unset or older.
     * It sets every milestone to {@code LOCKED}, every objective to zero and deletes {@code smp_contribution}.
     *
     * @return 1 when it reset the track, 0 otherwise
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

    /** Activates {@code key} when no milestone is active and exactly {@code completed} milestones are unlocked. */
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
