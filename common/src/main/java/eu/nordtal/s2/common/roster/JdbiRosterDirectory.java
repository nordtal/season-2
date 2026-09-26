package eu.nordtal.s2.common.roster;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * The only implementation of {@link RosterDirectory}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 * <p>
 * It borrows the pool it is given and owns nothing, which is why there is no {@code close()} here
 * and none on the interface - the process that built the pool closes the pool. That is
 * {@code JdbiUpdateDirectory}'s arrangement and not {@code JdbiAccessDirectory}'s: this is read-only
 * reporting, so there is no process whose only reason to touch the database is this API, and
 * therefore none that would need a pool of its own.
 * </p>
 */
final class JdbiRosterDirectory implements RosterDirectory {

    private final RosterDao dao;

    JdbiRosterDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(RosterDao.class);
    }

    @Override
    public List<Person> people(final int limit) {
        return dao.people(clamp(limit));
    }

    @Override
    public Optional<Person> personOf(final String discordId) {
        return dao.personOf(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public List<Payment> payments(final int limit) {
        return dao.payments(clamp(limit));
    }

    @Override
    public List<Payment> openPayments() {
        return dao.openPayments();
    }

    @Override
    public List<Grant> grantsOf(final String discordId) {
        return dao.grantsOf(Objects.requireNonNull(discordId, "discordId"));
    }

    /**
     * Clamped rather than rejected, exactly as {@code JdbiUpdateDirectory#recent} clamps it: a
     * caller computing a page size from a query parameter should get one row, not an exception out
     * of a page that is only trying to draw a table. {@code LIMIT 0} would be an empty table that
     * looks like an empty database.
     */
    private static int clamp(final int limit) {
        return Math.max(1, limit);
    }
}
