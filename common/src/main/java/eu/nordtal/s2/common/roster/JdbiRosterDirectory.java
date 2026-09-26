package eu.nordtal.s2.common.roster;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * The only implementation of {@link RosterDirectory}.
 *
 * It borrows the pool it is given and owns nothing, so there is no {@code close()}.
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

    /** Clamps a page size to at least one row, since {@code LIMIT 0} would look like an empty database. */
    private static int clamp(final int limit) {
        return Math.max(1, limit);
    }
}
