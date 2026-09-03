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
    public Optional<Instant> smpStart() {
        return dao.smpStart();
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

    @Override
    public DateChange setLaunch(final Instant at, final String actor) {
        refusePast(at, "The network cannot open in the past");
        final Instant smpStart = dao.smpStart().orElse(null);
        if (at != null && smpStart != null && smpStart.isBefore(at)) {
            throw new SeasonDateRefused(
                    "The network would open on " + SeasonDates.format(at) + ", after paid access"
                            + " starts running on " + SeasonDates.format(smpStart) + ". Move the"
                            + " SMP start first, or clear it.");
        }
        return written(dao.setLaunch(at, actor));
    }

    @Override
    public DateChange setSmpStart(final Instant at, final String actor) {
        // Read before the write and deliberately not inside it - see the interface. The point of
        // this check is the admin who has forgotten which phase the network is in, not two admins
        // racing each other.
        if (currentPhase() == SeasonPhase.SMP) {
            throw new SeasonDateRefused(
                    "The season is already in SMP, so paid time is being used up right now."
                            + " Moving the start date would hand somebody days they have played or"
                            + " take away days they have not. Change it in the database by hand if"
                            + " you are certain.");
        }
        refusePast(at, "Paid access cannot start running in the past");
        final Instant launch = dao.launch().orElse(null);
        if (at != null && launch != null && at.isBefore(launch)) {
            throw new SeasonDateRefused(
                    "Paid access would start running on " + SeasonDates.format(at) + ", before the"
                            + " network opens on " + SeasonDates.format(launch) + ". Nobody could"
                            + " use the days in between.");
        }
        return written(dao.setSmpStart(at, actor));
    }

    /** Clearing a date is always allowed; only a date that is set can be in the past. */
    private static void refusePast(final Instant at, final String what) {
        if (at != null && at.isBefore(Instant.now())) {
            throw new SeasonDateRefused(what + ". " + SeasonDates.format(at)
                    + " has already happened - use `" + SeasonDates.CLEAR
                    + "` if you meant to take the date away instead.");
        }
    }

    /** The same missing-row check {@link #switchPhase} makes, for the same reason. */
    private static DateChange written(final DateChange change) {
        if (change == null) {
            throw new IllegalStateException(
                    "The season_phase row is missing; the database has not had V4 applied, or the row was deleted by hand");
        }
        return change;
    }
}
