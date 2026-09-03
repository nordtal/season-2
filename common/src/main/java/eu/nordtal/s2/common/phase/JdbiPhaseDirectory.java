package eu.nordtal.s2.common.phase;

import eu.nordtal.s2.common.SeasonPhase;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The only implementation of {@link PhaseDirectory}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 * <p>
 * It borrows the pool it is given and owns nothing, which is why there is no {@code close()} here
 * and none on the interface - the process that built the pool closes the pool.
 * </p>
 */
final class JdbiPhaseDirectory implements PhaseDirectory {

    private final PhaseDao dao;

    JdbiPhaseDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(PhaseDao.class);
    }

    @Override
    public SeasonPhase currentPhase() {
        // fromDatabase(null) is MAINTENANCE, so a missing row and an unrecognised value both come
        // out as the phase that lets nobody in. A database that cannot be reached at all throws
        // instead - see the interface for why those two must not be the same answer.
        return SeasonPhase.fromDatabase(dao.currentPhase().orElse(null));
    }

    @Override
    public Optional<Instant> launch() {
        return dao.launch();
    }

    @Override
    public PhaseChange switchPhase(final SeasonPhase phase, final String actor, final String reason) {
        Objects.requireNonNull(phase, "phase");

        final PhaseChange change = dao.switchPhase(phase.name(), actor, reason);
        if (change == null) {
            // The statement matched no row, which means the season_phase singleton is gone. V4
            // seeds it and nothing in this codebase deletes it, so this is a corrupted database
            // rather than a state to recover from silently - and silently would mean an audit
            // entry for a switch that never happened.
            throw new IllegalStateException(
                    "The season_phase row is missing; the database has not had V4 applied, or the row was deleted by hand");
        }
        return change;
    }
}
