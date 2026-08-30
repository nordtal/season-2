package eu.nordtal.s2.paymentsbot.persistence.dao;

import eu.nordtal.s2.paymentsbot.persistence.model.Contribution;

import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBI SqlObject interface for the {@code contribution} table. This is the whole SQL surface of
 * the bot; everything above it ({@link ContributionRepository},
 * {@code eu.nordtal.s2.paymentsbot.service.ContributionService}) is plain Java.
 * <p>
 * The two queries here are the direct replacements for the JPA named queries
 * {@code Contribution.findByReceiver} and {@code Contribution.allProcessedPaymentIds} that jcore
 * 1.x carried on the entity.
 * </p>
 */
public interface ContributionDao {

    /**
     * Inserts a new contribution and returns the {@code uuid} PostgreSQL generated for it.
     * <p>
     * {@code id} is deliberately absent from the column list: the column's
     * {@code DEFAULT gen_random_uuid()} produces it and {@code @GetGeneratedKeys} reads it back
     * through {@code RETURNING id}. jcore 1.x silently returned a detached instance with a
     * {@code null} id here, which callers then logged as {@code null}.
     * </p>
     *
     * @param contribution the row to insert; its {@code id} is ignored
     * @return the generated primary key
     */
    @SqlUpdate("""
            INSERT INTO contribution (receiver_id, euro_amount, created, duration_seconds, bunq_payment_id)
            VALUES (:receiverId, :euroAmount, :created, :durationSeconds, :bunqPaymentId)
            """)
    @GetGeneratedKeys("id")
    UUID insert(@BindBean Contribution contribution);

    @SqlQuery("SELECT * FROM contribution WHERE id = :id")
    @RegisterBeanMapper(Contribution.class)
    Optional<Contribution> findById(@Bind("id") UUID id);

    @SqlQuery("SELECT * FROM contribution")
    @RegisterBeanMapper(Contribution.class)
    List<Contribution> findAll();

    /**
     * Every contribution of one receiver, oldest first, higher amounts before lower ones on a tie.
     * That is exactly the order
     * {@link ContributionRepository#resolveActiveContribution(List, java.time.LocalDateTime)}
     * expects; it re-sorts defensively, so this ordering is a guarantee, not a requirement.
     */
    @SqlQuery("""
            SELECT * FROM contribution
            WHERE receiver_id = :receiverId
            ORDER BY created ASC, euro_amount DESC
            """)
    @RegisterBeanMapper(Contribution.class)
    List<Contribution> findByReceiver(@Bind("receiverId") String receiverId);

    /**
     * Every bunq payment id already booked, including the {@code -1} sentinel of synthetic rows.
     * The poll loop diffs the bunq API's payment list against this set.
     */
    @SqlQuery("SELECT bunq_payment_id FROM contribution")
    Set<Long> allProcessedPaymentIds();

    @SqlUpdate("DELETE FROM contribution WHERE id = :id")
    int deleteById(@Bind("id") UUID id);
}
