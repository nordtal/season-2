package eu.nordtal.season.database.season;

import static eu.nordtal.season.database.DatabaseMessages.MESSAGES;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonOpening;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.SeasonDetail;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.messages.Refused;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

final class JdbiSeasonDirectory implements SeasonDirectory {

    private static final String MISSING =
            "The season_state row is missing; the database has not had V113 applied, or the row was deleted by hand";

    private final Jdbi jdbi;
    private final SeasonDao dao;
    private final Clock clock;

    JdbiSeasonDirectory(final DataSource dataSource, final Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jdbi = Jdbis.over(dataSource);
        this.dao = jdbi.onDemand(SeasonDao.class);
    }

    @Override
    public SeasonState state() {
        return dao.state().orElse(SeasonState.UNREAD);
    }

    @Override
    public int anchoredGrants() {
        return dao.anchoredGrants();
    }

    @Override
    public Optional<MaintenanceSpan> lastMaintenance() {
        return dao.lastMaintenance();
    }

    @Override
    public SeasonChange setLaunch(final Instant at, final Actor actor, final @Nullable String reason) {
        Objects.requireNonNull(at, "at");
        final Instant now = clock.instant();
        if (at.isBefore(now)) {
            throw new Refused(SeasonRefusal.IN_THE_PAST, MESSAGES.season().launchInThePast(at, now));
        }
        return written(
                seasons -> seasons.setLaunch(at),
                JournalAction.SET_LAUNCH_DATE,
                actor,
                change -> new SeasonDetail.LaunchDateSet(change.previous().launch(), at, change.grants(), reason));
    }

    @Override
    public SeasonChange launchNow(final Actor actor, final @Nullable String reason) {
        // Read outside the write, as this guards an admin's slip, not a race.
        final Instant now = clock.instant();
        final SeasonState state = state();
        if (state.opening(now) == SeasonOpening.LAUNCHED) {
            throw new Refused(
                    SeasonRefusal.ALREADY_LAUNCHED,
                    MESSAGES.season().alreadyLaunched(Objects.requireNonNull(state.launch(), "launch")));
        }
        return written(
                seasons -> seasons.setLaunch(now),
                JournalAction.LAUNCH_NOW,
                actor,
                change -> new SeasonDetail.LaunchedNow(change.previous().launch(), now, change.grants(), reason));
    }

    @Override
    public SeasonChange closeSeason(final Actor actor, final @Nullable String reason) {
        final SeasonState state = state();
        final Instant launch = state.launch();
        if (launch == null || state.opening(clock.instant()) != SeasonOpening.LAUNCHED) {
            throw new Refused(SeasonRefusal.NOT_LAUNCHED, MESSAGES.season().notLaunched());
        }
        return written(
                seasons -> seasons.setLaunch(null),
                JournalAction.CLOSE_SEASON,
                actor,
                change -> new SeasonDetail.SeasonClosed(
                        Objects.requireNonNullElse(change.previous().launch(), launch), reason));
    }

    @Override
    public SeasonChange setMaintenance(final boolean on, final Actor actor, final @Nullable String reason) {
        return written(
                seasons -> seasons.setMaintenance(on),
                JournalAction.SET_MAINTENANCE,
                actor,
                change -> new SeasonDetail.MaintenanceSet(on, reason));
    }

    @Override
    public SeasonChange setMode(final SeasonMode mode, final Actor actor, final @Nullable String reason) {
        Objects.requireNonNull(mode, "mode");
        return written(
                seasons -> seasons.setMode(mode.name()),
                JournalAction.SWITCH_MODE,
                actor,
                change -> new SeasonDetail.ModeSwitched(change.previous().mode(), mode, reason));
    }

    /** Writes the row and, if it changed, its journal entry in one transaction. A missing row is an error. */
    private SeasonChange written(
            final Function<SeasonDao, @Nullable SeasonChange> write,
            final JournalAction action,
            final Actor actor,
            final Function<SeasonChange, JournalDetail> line) {
        return jdbi.inTransaction(handle -> {
            final @Nullable SeasonChange change = write.apply(handle.attach(SeasonDao.class));
            if (change == null) {
                // The singleton row is gone: a corrupted database gets no journal entry.
                throw new IllegalStateException(MISSING);
            }
            if (!change.unchanged()) {
                Journal.write(handle, AuditLine.of(action, actor, line.apply(change)));
            }
            return change;
        });
    }
}
