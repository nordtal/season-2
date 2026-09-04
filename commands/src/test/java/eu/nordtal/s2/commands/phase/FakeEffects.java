package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.phase.SeasonDateRefused;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A process that answers {@code /phase}, scripted.
 *
 * <p>{@link #async} runs inline. That is not a shortcut: the thing under test is what a command
 * decides and in which order it says it, and a thread would only make the assertions racy without
 * proving anything the real schedulers do not already own.</p>
 */
final class FakeEffects implements PhaseEffects, PhaseDirectory {

    SeasonPhase current = SeasonPhase.PRE_LAUNCH;
    Instant launch;
    Instant smpStart;

    /** Set to make the next read or write throw, which is the branch nobody exercises by hand. */
    RuntimeException readFailure;
    RuntimeException writeFailure;
    SeasonDateRefused dateRefusal;

    /** What {@code setSmpStart} reports as moved, so the "somebody else's money" branch is testable. */
    int grants;
    int accounts;

    Observation observation;

    final List<String> warnings = new ArrayList<>();
    final List<PhaseChange> recordedSwitches = new ArrayList<>();
    final List<String> recordedDates = new ArrayList<>();
    int afterWrites;
    String lastActor;
    String lastReason;

    @Override
    public PhaseDirectory phases() {
        return this;
    }

    @Override
    public Optional<Observation> observation() {
        return Optional.ofNullable(observation);
    }

    @Override
    public void afterWrite() {
        afterWrites++;
    }

    @Override
    public void recordSwitch(final NordtalUser who, final PhaseChange change) {
        recordedSwitches.add(change);
    }

    @Override
    public void recordDate(final NordtalUser who, final boolean isLaunch, final DateChange change) {
        recordedDates.add((isLaunch ? "launch " : "smp-start ") + change.current());
    }

    @Override
    public void async(final Runnable work) {
        work.run();
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        warnings.add(what);
    }

    // ---------------------------------------------------------------- PhaseDirectory

    @Override
    public SeasonPhase currentPhase() {
        if (readFailure != null) {
            throw readFailure;
        }
        return current;
    }

    @Override
    public PhaseChange switchPhase(final SeasonPhase phase, final String actor, final String reason) {
        if (writeFailure != null) {
            throw writeFailure;
        }
        lastActor = actor;
        lastReason = reason;
        final SeasonPhase previous = current;
        current = phase;
        return new PhaseChange(previous, phase, Instant.EPOCH);
    }

    @Override
    public Optional<Instant> launch() {
        if (readFailure != null) {
            throw readFailure;
        }
        return Optional.ofNullable(launch);
    }

    @Override
    public Optional<Instant> smpStart() {
        if (readFailure != null) {
            throw readFailure;
        }
        return Optional.ofNullable(smpStart);
    }

    @Override
    public DateChange setLaunch(final Instant at, final String actor) {
        return writeDate(at, actor, true);
    }

    @Override
    public DateChange setSmpStart(final Instant at, final String actor) {
        return writeDate(at, actor, false);
    }

    private DateChange writeDate(final Instant at, final String actor, final boolean isLaunch) {
        if (dateRefusal != null) {
            throw dateRefusal;
        }
        if (writeFailure != null) {
            throw writeFailure;
        }
        lastActor = actor;
        final Instant previous = isLaunch ? launch : smpStart;
        if (isLaunch) {
            launch = at;
        } else {
            smpStart = at;
        }
        return new DateChange(previous, at, grants, accounts);
    }
}
