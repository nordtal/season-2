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

    record Submitted(UpdateKind kind, UpdateSource source, String requestedBy, Duration delay) {
    }

    final List<Submitted> submitted = new ArrayList<>();
    private long nextId = 1L;

    @Override
    public UpdateRequest submit(final UpdateKind kind, final UpdateSource source,
                                final String requestedBy, final Duration delay) {
        submitted.add(new Submitted(kind, source, requestedBy, delay));
        final Instant now = Instant.now();
        return new UpdateRequest(nextId++, kind, UpdateStatus.PENDING, source, requestedBy, now,
                now.plus(delay), null, null, null);
    }

    @Override
    public Optional<UpdateRequest> find(final long id) {
        throw new UnsupportedOperationException("not read in these tests");
    }

    @Override
    public Optional<UpdateRequest> claimNext() {
        throw new UnsupportedOperationException("only the updater claims");
    }

    @Override
    public Optional<UpdateRequest> finish(final long id, final UpdateStatus status,
                                          final String result) {
        throw new UnsupportedOperationException("only the updater finishes");
    }

    @Override
    public boolean progress(final long id, final String result) {
        throw new UnsupportedOperationException("only the updater reports progress");
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
    public Optional<UpdateRequest> startCountdown(final long id, final java.time.Duration seconds) {
        throw new UnsupportedOperationException("only the updater counts down");
    }

    @Override
    public boolean commitCountdown(final long id) {
        throw new UnsupportedOperationException("only the updater counts down");
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
        throw new UnsupportedOperationException("only the updater asks");
    }

    @Override
    public int settleOrphans(final String failed) {
        throw new UnsupportedOperationException("only the updater settles");
    }
}
