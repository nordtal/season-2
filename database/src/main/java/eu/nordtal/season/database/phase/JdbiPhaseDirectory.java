package eu.nordtal.season.database.phase;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static eu.nordtal.season.database.DatabaseMessages.MESSAGES;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Refused;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** The only implementation of {@link PhaseDirectory}; it borrows the pool it is given and owns nothing. */
final class JdbiPhaseDirectory implements PhaseDirectory {

    private static final String MISSING =
            "The season_phase row is missing; the database has not had V4 applied, or the row was deleted by hand";

    private final Jdbi jdbi;
    private final PhaseDao dao;
    private final Clock clock;

    JdbiPhaseDirectory(final DataSource dataSource, final Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jdbi = Jdbis.over(dataSource);
        this.dao = jdbi.onDemand(PhaseDao.class);
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
    public PhaseChange switchPhase(final SeasonPhase phase, final Actor actor, final @Nullable String reason) {
        Objects.requireNonNull(phase, "phase");

        return jdbi.inTransaction(handle -> {
            final @Nullable PhaseChange change = handle.attach(PhaseDao.class).switchPhase(phase.name());
            if (change == null) {
                // No row matched: the singleton is gone, a corrupted database that must not get an audit entry.
                throw new IllegalStateException(MISSING);
            }
            Journal.write(
                    handle,
                    AuditLine.of(
                            JournalAction.SET_PHASE,
                            actor,
                            reason == null || reason.isBlank()
                                    ? TEXTS.journal().setPhase(change.previous(), change.current())
                                    : TEXTS.journal().setPhaseBecause(change.previous(), change.current(), reason)));
            return change;
        });
    }

    @Override
    public DateChange setLaunch(final @Nullable Instant at, final Actor actor) {
        if (at != null && at.isBefore(clock.instant())) {
            throw new Refused(SeasonDateRefusal.IN_THE_PAST, MESSAGES.season().launchInThePast(at, SeasonDates.CLEAR));
        }
        final Instant smpStart = dao.smpStart().orElse(null);
        if (at != null && smpStart != null && smpStart.isBefore(at)) {
            throw new Refused(SeasonDateRefusal.OUT_OF_ORDER, MESSAGES.season().launchAfterSmpStart(at, smpStart));
        }
        return written(
                phases -> phases.setLaunch(at),
                JournalAction.SET_LAUNCH,
                actor,
                change -> change.current() == null
                        ? TEXTS.journal().clearLaunch()
                        : TEXTS.journal().setLaunch(change.current()));
    }

    @Override
    public DateChange setSmpStart(final @Nullable Instant at, final Actor actor) {
        // Read outside the write on purpose: this guards a forgetful admin, not a race.
        if (currentPhase() == SeasonPhase.SMP) {
            throw new Refused(SeasonDateRefusal.SMP_RUNNING, MESSAGES.season().smpRunning());
        }
        if (at != null && at.isBefore(clock.instant())) {
            throw new Refused(
                    SeasonDateRefusal.IN_THE_PAST, MESSAGES.season().smpStartInThePast(at, SeasonDates.CLEAR));
        }
        final Instant launch = dao.launch().orElse(null);
        if (at != null && launch != null && at.isBefore(launch)) {
            throw new Refused(SeasonDateRefusal.OUT_OF_ORDER, MESSAGES.season().smpStartBeforeLaunch(at, launch));
        }
        return written(
                phases -> phases.setSmpStart(at),
                JournalAction.SET_SMP_START,
                actor,
                change -> change.current() == null
                        ? TEXTS.journal().clearSmpStart()
                        : TEXTS.journal().setSmpStart(change.current(), change.grants()));
    }

    /** Writes a date and its journal line in one transaction, with the missing-row check {@link #switchPhase} makes. */
    private DateChange written(
            final Function<PhaseDao, @Nullable DateChange> write,
            final JournalAction action,
            final Actor actor,
            final Function<DateChange, MessageRef> line) {
        return jdbi.inTransaction(handle -> {
            final @Nullable DateChange change = write.apply(handle.attach(PhaseDao.class));
            if (change == null) {
                throw new IllegalStateException(MISSING);
            }
            Journal.write(handle, AuditLine.of(action, actor, line.apply(change)));
            return change;
        });
    }
}
