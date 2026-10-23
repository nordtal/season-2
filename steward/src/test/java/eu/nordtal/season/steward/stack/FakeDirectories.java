package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Fixed-row {@link UpdateDirectory} and {@link AuditDirectory} for a {@link StackApi} without PostgreSQL. */
public final class FakeDirectories {

    private FakeDirectories() {}

    /** Reads {@code rows} in {@code recent}, {@code open} and {@code finishedWithin}. Every other method throws. */
    public static UpdateDirectory updates(final UpdateRequest... rows) {
        final List<UpdateRequest> sorted = List.of(rows).stream()
                .sorted(Comparator.comparing(
                                (UpdateRequest run) -> run.finished() != null ? run.finished() : run.requested())
                        .reversed())
                .toList();
        return new ThrowingUpdateDirectory(sorted);
    }

    /** Returns the sorted fixed rows of {@link UpdateDirectory#recent(int)}. */
    private static final class ThrowingUpdateDirectory implements UpdateDirectory {

        private final List<UpdateRequest> sorted;

        ThrowingUpdateDirectory(final List<UpdateRequest> sorted) {
            this.sorted = sorted;
        }

        @Override
        public UpdateRequest submit(
                final eu.nordtal.season.database.inbox.StewardRequest request,
                final java.util.List<String> services,
                final Actor actor,
                final Duration delay) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<UpdateRequest> find(final long id) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public List<UpdateRequest> recent(final int limit) {
            final int clamped = Math.max(1, limit);
            return sorted.size() > clamped ? sorted.subList(0, clamped) : sorted;
        }

        @Override
        public List<UpdateRequest> since(final long id) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public long latestId() {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<UpdateRequest> open() {
            return sorted.stream()
                    .filter(run -> run.status() == UpdateStatus.PENDING || run.status() == UpdateStatus.RUNNING)
                    .findFirst();
        }

        /** Returns every finished row, regardless of the window. */
        @Override
        public List<UpdateRequest> finishedWithin(final Duration window) {
            return sorted.stream().filter(run -> run.finished() != null).toList();
        }

        @Override
        public Optional<UpdateRequest> lastSuccessfulBackup(final Duration within) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<UpdateRequest> claimNext() {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<UpdateRequest> finish(final long id, final eu.nordtal.season.database.update.RunStage stage) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public boolean progress(final long id, final eu.nordtal.season.database.update.RunStage stage) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<UpdateRequest> startCountdown(
                final long id, final Duration length, final java.util.Collection<String> moving) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public boolean commitCountdown(final long id) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<UpdateRequest> countingDown() {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<UpdateRequest> running() {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<UpdateRequest> cancelCountdown() {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<Instant> nextDue() {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public int settleOrphans(final java.util.function.Predicate<String> stillRunning) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }
    }
}
