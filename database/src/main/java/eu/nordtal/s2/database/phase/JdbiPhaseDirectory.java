package eu.nordtal.s2.database.phase;

import static eu.nordtal.s2.database.DatabaseMessages.MESSAGES;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.messages.Refused;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jspecify.annotations.Nullable;

/** The only implementation of {@link PhaseDirectory}; it borrows the pool it is given and owns nothing. */
final class JdbiPhaseDirectory implements PhaseDirectory {

    private final PhaseDao dao;
    private final InstantSource clock;

    JdbiPhaseDirectory(final DataSource dataSource, final InstantSource clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        if (at != null && at.isBefore(clock.instant())) {
            throw new Refused(
                    SeasonDateRefusal.IN_THE_PAST,
                    MESSAGES.season().launchInThePast(SeasonDates.format(at), SeasonDates.CLEAR));
        }
        final Instant smpStart = dao.smpStart().orElse(null);
        if (at != null && smpStart != null && smpStart.isBefore(at)) {
            throw new Refused(
                    SeasonDateRefusal.OUT_OF_ORDER,
                    MESSAGES.season().launchAfterSmpStart(SeasonDates.format(at), SeasonDates.format(smpStart)));
        }
        return written(dao.setLaunch(at, actor));
    }

    @Override
    public DateChange setSmpStart(final @Nullable Instant at, final @Nullable String actor) {
        // Read outside the write on purpose: this guards a forgetful admin, not a race.
        if (currentPhase() == SeasonPhase.SMP) {
            throw new Refused(SeasonDateRefusal.SMP_RUNNING, MESSAGES.season().smpRunning());
        }
        if (at != null && at.isBefore(clock.instant())) {
            throw new Refused(
                    SeasonDateRefusal.IN_THE_PAST,
                    MESSAGES.season().smpStartInThePast(SeasonDates.format(at), SeasonDates.CLEAR));
        }
        final Instant launch = dao.launch().orElse(null);
        if (at != null && launch != null && at.isBefore(launch)) {
            throw new Refused(
                    SeasonDateRefusal.OUT_OF_ORDER,
                    MESSAGES.season().smpStartBeforeLaunch(SeasonDates.format(at), SeasonDates.format(launch)));
        }
        return written(dao.setSmpStart(at, actor));
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
