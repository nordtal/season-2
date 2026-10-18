package eu.nordtal.season.smp.progress;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifies;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * Writes the amounts, contributions and completion of objectives.
 * It is never called from the main thread, and {@link ObjectiveEngine} calls it inside its transactions.
 */
@Notifies(Channel.SMP)
public interface ProgressDao {

    /** Adds {@code delta} to {@code smp_objective.amount} without a cap at the target. */
    @SqlQuery("""
            WITH updated AS (UPDATE smp_objective SET amount = amount + :delta WHERE id = :id RETURNING id)
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM updated) AS notified
            """)
    int addObjectiveProgress(@Bind("id") UUID id, @Bind("delta") long delta);

    @SqlUpdate("""
            INSERT INTO smp_contribution (objective_id, discord_id, amount)
            VALUES (:objectiveId, :discordId, :amount)
            ON CONFLICT (objective_id, discord_id) DO UPDATE
                SET amount  = smp_contribution.amount + excluded.amount,
                    updated = now()
            """)
    void addContribution(
            @Bind("objectiveId") UUID objectiveId, @Bind("discordId") DiscordId discordId, @Bind("amount") long amount);

    /**
     * Inserts a contribution of 1 and adds 1 to {@code smp_objective.amount}.
     * It does nothing when the player has a contribution or the objective is completed.
     *
     * @return the new amount, or empty when nothing was added
     */
    @SqlQuery("""
            WITH counted AS (
                INSERT INTO smp_contribution (objective_id, discord_id, amount)
                SELECT obj.id, :discordId, 1
                FROM smp_objective obj
                WHERE obj.id = :objectiveId AND obj.completed IS NULL
                ON CONFLICT (objective_id, discord_id) DO NOTHING
                RETURNING objective_id
            ),
            updated AS (
                UPDATE smp_objective obj
                SET amount = obj.amount + 1
                FROM counted
                WHERE obj.id = counted.objective_id
                RETURNING obj.amount
            )
            SELECT updated.amount, pg_notify(:channel, '') AS notified FROM updated
            """)
    Optional<Long> countOnce(@Bind("objectiveId") UUID objectiveId, @Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT discord_id AS discordId, amount AS amount
            FROM smp_contribution
            WHERE objective_id = :objectiveId AND amount > 0
            """)
    @RegisterConstructorMapper(ContributionRow.class)
    List<ContributionRow> contributionsOf(@Bind("objectiveId") UUID objectiveId);

    /** Returns one row per objective of the milestone, with {@code mine} zero where the player has no contribution. */
    @SqlQuery("""
            SELECT obj.key               AS key,
                   coalesce(con.amount, 0) AS mine,
                   obj.amount            AS reached,
                   obj.target            AS target
            FROM smp_objective obj
            LEFT JOIN smp_contribution con
                   ON con.objective_id = obj.id AND con.discord_id = :discordId
            WHERE obj.milestone_key = :milestoneKey
            """)
    @RegisterConstructorMapper(OwnAmountRow.class)
    List<OwnAmountRow> ownContributions(
            @Bind("milestoneKey") String milestoneKey, @Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT obj.key AS key, con.discord_id AS discordId, con.amount AS amount
            FROM smp_objective obj
            JOIN smp_contribution con ON con.objective_id = obj.id
            WHERE obj.milestone_key = :milestoneKey AND con.amount > 0
            """)
    @RegisterConstructorMapper(KeyedContributionRow.class)
    List<KeyedContributionRow> contributionsUnder(@Bind("milestoneKey") String milestoneKey);

    /**
     * Sets {@code smp_objective.completed} when it is unset.
     *
     * @return 1 for the one call that set it, 0 otherwise
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE smp_objective SET completed = now() WHERE id = :id AND completed IS NULL RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM updated) AS notified
            """)
    int completeObjective(@Bind("id") UUID id);
}
