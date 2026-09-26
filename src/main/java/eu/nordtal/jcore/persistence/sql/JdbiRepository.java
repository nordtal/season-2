package eu.nordtal.jcore.persistence.sql;

import java.util.Objects;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;

/**
 * Optional thin base class for a repository backed by a JDBI SqlObject DAO interface.
 * <p>
 * It holds nothing but the {@link Jdbi} instance and one on-demand DAO proxy, so subclasses can
 * write {@code dao().findByUuid(uuid)} instead of repeating the {@code jdbi.onDemand(...)} wiring.
 * There is deliberately <b>no</b> generic {@code save}/{@code findFirst(field, value)} surface here:
 * under JDBI the typed DAO interface is the abstraction, and re-introducing string field names
 * would throw away the type safety that motivated the 2.0 rewrite.
 * </p>
 * <p>
 * Extending this class is entirely optional - {@code database.jdbi().onDemand(MyDao.class)} is a
 * perfectly good alternative and does not require jcore types in the consumer's hierarchy.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public interface PaymentDao {
 *     @SqlQuery("SELECT * FROM payments WHERE id = :id")
 *     @RegisterConstructorMapper(Payment.class)
 *     Optional<Payment> findById(@Bind("id") long id);
 * }
 *
 * public final class PaymentRepository extends JdbiRepository<PaymentDao> {
 *     public PaymentRepository(Database database) {
 *         super(database, PaymentDao.class);
 *     }
 *
 *     public Optional<Payment> byId(long id) {
 *         return dao().findById(id);
 *     }
 * }
 * }</pre>
 *
 * @param <D> the SqlObject DAO interface type
 */
public abstract class JdbiRepository<D> {

    private final Jdbi jdbi;
    private final D dao;

    /**
     * @param database the {@link Database} whose {@link Jdbi} instance backs this repository
     * @param daoType the SqlObject DAO interface to attach on demand
     */
    protected JdbiRepository(final @NotNull Database database, final @NotNull Class<D> daoType) {
        this(Objects.requireNonNull(database, "database").jdbi(), daoType);
    }

    /**
     * @param jdbi the {@link Jdbi} instance backing this repository
     * @param daoType the SqlObject DAO interface to attach on demand
     */
    protected JdbiRepository(final @NotNull Jdbi jdbi, final @NotNull Class<D> daoType) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.dao = jdbi.onDemand(Objects.requireNonNull(daoType, "daoType"));
    }

    /**
     * @return the on-demand DAO proxy; every call on it borrows and returns a connection by itself
     *         and joins an ambient transaction when there is one
     */
    protected final @NotNull D dao() {
        return dao;
    }

    /**
     * @return the underlying {@link Jdbi} instance, for queries that do not fit the DAO interface
     *         or for opening an explicit transaction across several DAO calls
     */
    protected final @NotNull Jdbi jdbi() {
        return jdbi;
    }
}
