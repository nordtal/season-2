package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link UpdateDirectory} and {@link AuditDirectory} over fixed rows, for a {@link StackApi} without PostgreSQL.
 *
 * Every method but the listing ones throws, so a test cannot silently write.
 */
public final class FakeDirectories {

    private FakeDirectories() {}

    /** Every method throws except {@code recent}, {@code open} and {@code finishedWithin}, which read {@code rows}. */
    public static UpdateDirectory updates(final UpdateRequest... rows) {
        final List<UpdateRequest> sorted = List.of(rows).stream()
                .sorted(Comparator.comparing(
                                (UpdateRequest run) -> run.finished() != null ? run.finished() : run.requested())
                        .reversed())
                .toList();
        return new ThrowingUpdateDirectory(sorted);
    }

    /** {@link UpdateDirectory#recent(int)} answers a fixed, pre-sorted list; every other method throws. */
    private static final class ThrowingUpdateDirectory implements UpdateDirectory {

        private final List<UpdateRequest> sorted;

        ThrowingUpdateDirectory(final List<UpdateRequest> sorted) {
            this.sorted = sorted;
        }

        @Override
        public UpdateRequest submit(
                final eu.nordtal.season.database.inbox.StewardRequest request,
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

        /** Every finished row, whatever the window, since this fake keeps no clock. */
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
        public Optional<UpdateRequest> finish(final long id, final UpdateStatus status, final String result) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public boolean progress(final long id, final String result) {
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
        public int settleOrphans(
                final eu.nordtal.season.messages.MessageRef why,
                final java.util.function.Predicate<String> stillRunning) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }
    }

    /** Every method throws except {@code recent} and {@code search}, which filters by action as the real one does. */
    public static AuditDirectory audit(final AuditEntry... rows) {
        final List<AuditEntry> sorted = List.of(rows).stream()
                .sorted(Comparator.comparing(AuditEntry::occurred).reversed())
                .toList();
        return new AuditDirectory() {
            @Override
            public List<AuditEntry> recent(final int limit) {
                final int clamped = Math.max(1, limit);
                return sorted.size() > clamped ? sorted.subList(0, clamped) : sorted;
            }

            @Override
            public List<AuditEntry> search(final String action, final String subject, final int limit) {
                final int clamped = Math.max(1, limit);
                final List<AuditEntry> filtered = sorted.stream()
                        .filter(entry -> action == null || action.isBlank() || action.equals(entry.action()))
                        .filter(entry -> subject == null
                                || subject.isBlank()
                                || DiscordId.of(subject).equals(entry.subject()))
                        .toList();
                return filtered.size() > clamped ? filtered.subList(0, clamped) : filtered;
            }

            @Override
            public void record(final AuditLine line) {
                throw new UnsupportedOperationException("not exercised by this fake");
            }
        };
    }
}
