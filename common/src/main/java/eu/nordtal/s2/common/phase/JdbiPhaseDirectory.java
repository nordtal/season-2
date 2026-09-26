package eu.nordtal.s2.common.phase;

import eu.nordtal.s2.common.SeasonPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The only implementation of {@link PhaseDirectory}.
 *
 * It borrows the pool it is given and owns nothing, so there is no {@code close()}.
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
        // A missing row or unknown value is MAINTENANCE; an unreachable database throws instead.
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
    public PhaseChange switchPhase(
            final SeasonPhase phase, final @Nullable String actor, final @Nullable String reason) {
        Objects.requireNonNull(phase, "phase");

        final @Nullable PhaseChange change = dao.switchPhase(phase.name(), actor, reason);
        if (change == null) {
            // No row matched: the singleton is gone, a corrupted database that must not get an audit entry.
            throw new IllegalStateException(
                    "The season_phase row is missing; the database has not had V4 applied, or the row was deleted by hand");
        }
        return change;
    }

    @Override
    public DateChange setLaunch(final @Nullable Instant at, final @Nullable String actor) {
        refusePast(at, "The network cannot open in the past");
        final Instant smpStart = dao.smpStart().orElse(null);
        if (at != null && smpStart != null && smpStart.isBefore(at)) {
            throw new SeasonDateRefused("The network would open on " + SeasonDates.format(at) + ", after paid access"
                    + " starts running on " + SeasonDates.format(smpStart) + ". Move the"
                    + " SMP start first, or clear it.");
        }
        return written(dao.setLaunch(at, actor));
    }

    @Override
    public DateChange setSmpStart(final @Nullable Instant at, final @Nullable String actor) {
        // Read outside the write on purpose: this guards a forgetful admin, not a race.
        if (currentPhase() == SeasonPhase.SMP) {
            throw new SeasonDateRefused("The season is already in SMP, so paid time is being used up right now."
                    + " Moving the start date would hand somebody days they have played or"
                    + " take away days they have not. Change it in the database by hand if"
                    + " you are certain.");
        }
        refusePast(at, "Paid access cannot start running in the past");
        final Instant launch = dao.launch().orElse(null);
        if (at != null && launch != null && at.isBefore(launch)) {
            throw new SeasonDateRefused("Paid access would start running on " + SeasonDates.format(at) + ", before the"
                    + " network opens on " + SeasonDates.format(launch) + ". Nobody could"
                    + " use the days in between.");
        }
        return written(dao.setSmpStart(at, actor));
    }

    /** Clearing a date is always allowed; only a date that is set can be in the past. */
    private static void refusePast(final @Nullable Instant at, final String what) {
        if (at != null && at.isBefore(Instant.now())) {
            throw new SeasonDateRefused(what + ". " + SeasonDates.format(at)
                    + " has already happened - use `" + SeasonDates.CLEAR
                    + "` if you meant to take the date away instead.");
        }
    }

    /** The same missing-row check {@link #switchPhase} makes, for the same reason. */
    private static DateChange written(final @Nullable DateChange change) {
        if (change == null) {
            throw new IllegalStateException(
                    "The season_phase row is missing; the database has not had V4 applied, or the row was deleted by hand");
        }
        return change;
    }
}
