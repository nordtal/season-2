package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** An in-memory {@link UpdateDirectory}: remembers what was submitted, answers what it is told. */
final class FakeUpdateDirectory implements UpdateDirectory {

    record Submitted(UpdateKind kind, UpdateSource source, String requestedBy, Duration delay, List<String> services) {}

    final List<Submitted> submitted = new ArrayList<>();
    /** When set, every submit is refused with it, the way the real directory refuses a second run. */
    eu.nordtal.s2.common.update.RunRefused refusing;

    private long nextId = 1L;

    @Override
    public UpdateRequest submit(
            final UpdateKind kind, final UpdateSource source, final String requestedBy, final Duration delay) {
        return submit(kind, source, requestedBy, delay, List.of());
    }

    /**
     * Overridden rather than left to the default, which drops the scope on the floor.
     *
     * A test that could not see which services a command asked for could not tell
     * {@code /update down smp} from {@code /update down} - and the second one is the whole network
     * held down.
     */
    @Override
    public UpdateRequest submit(
            final UpdateKind kind,
            final UpdateSource source,
            final String requestedBy,
            final Duration delay,
            final List<String> services) {
        if (refusing != null) {
            throw refusing;
        }
        submitted.add(new Submitted(kind, source, requestedBy, delay, List.copyOf(services)));
        final Instant now = Instant.now();
        return new UpdateRequest(
                nextId++, kind, UpdateStatus.PENDING, source, requestedBy, now, now.plus(delay), null, null, null);
    }

    @Override
    public Optional<UpdateRequest> find(final long id) {
        throw new UnsupportedOperationException("not read in these tests");
    }

    @Override
    public Optional<UpdateRequest> claimNext() {
        throw new UnsupportedOperationException("only steward-worker claims");
    }

    @Override
    public Optional<UpdateRequest> finish(final long id, final UpdateStatus status, final String result) {
        throw new UnsupportedOperationException("only steward-worker finishes");
    }

    @Override
    public boolean progress(final long id, final String result) {
        throw new UnsupportedOperationException("only steward-worker reports progress");
    }

    @Override
    public java.util.List<UpdateRequest> since(final long id) {
        throw new UnsupportedOperationException("only the bot's feed reads the table forward");
    }

    @Override
    public long latestId() {
        throw new UnsupportedOperationException("only the bot's feed reads the table forward");
    }

    @Override
    public java.util.List<UpdateRequest> finishedWithin(final java.time.Duration window) {
        throw new UnsupportedOperationException("only the bot's feed reads the table forward");
    }

    @Override
    public Optional<UpdateRequest> lastSuccessfulBackup(final java.time.Duration within) {
        throw new UnsupportedOperationException("nothing in this module asks whether a backup exists");
    }

    @Override
    public Optional<UpdateRequest> startCountdown(final long id, final java.time.Duration seconds) {
        throw new UnsupportedOperationException("only steward-worker counts down");
    }

    @Override
    public boolean commitCountdown(final long id) {
        throw new UnsupportedOperationException("only steward-worker counts down");
    }

    @Override
    public Optional<UpdateRequest> countingDown() {
        throw new UnsupportedOperationException("not read in these tests");
    }

    @Override
    public Optional<UpdateRequest> cancelCountdown(final String reason) {
        throw new UnsupportedOperationException("not cancelled in these tests");
    }

    @Override
    public Optional<Instant> nextDue() {
        throw new UnsupportedOperationException("only steward-worker asks");
    }

    @Override
    public int settleOrphans(final String failed) {
        throw new UnsupportedOperationException("only steward-worker settles");
    }

    @Override
    public java.util.Optional<eu.nordtal.s2.common.update.UpdateRequest> running() {
        return java.util.Optional.empty();
    }

    @Override
    public java.util.List<UpdateRequest> recent(final int limit) {
        // Nothing in this fake ever lists: the list is a page in the interface, not a decision anything here makes.
        return java.util.List.of();
    }
}
