package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.audit.AuditDirectory;
import eu.nordtal.s2.common.audit.AuditEntry;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link UpdateDirectory} and {@link AuditDirectory} over fixed rows, for a {@link WorkerApi} without PostgreSQL.
 *
 * Every method but the listing ones throws, so a test cannot silently write.
 */
final class FakeDirectories {

    private FakeDirectories() {}

    /** Every method throws except {@link UpdateDirectory#recent(int)}, which answers {@code rows}. */
    static UpdateDirectory updates(final UpdateRequest... rows) {
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
                final UpdateKind kind, final UpdateSource source, final String requestedBy, final Duration delay) {
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
        public List<UpdateRequest> finishedWithin(final Duration window) {
            throw new UnsupportedOperationException("not exercised by this fake");
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
        public Optional<UpdateRequest> startCountdown(final long id, final Duration length) {
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
        public Optional<UpdateRequest> cancelCountdown(final String reason) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public Optional<Instant> nextDue() {
            throw new UnsupportedOperationException("not exercised by this fake");
        }

        @Override
        public int settleOrphans(final String failed) {
            throw new UnsupportedOperationException("not exercised by this fake");
        }
    }

    /** Every method throws except {@code recent} and {@code search}, which filters by action as the real one does. */
    static AuditDirectory audit(final AuditEntry... rows) {
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
                        .filter(entry -> subject == null || subject.isBlank() || subject.equals(entry.subject()))
                        .toList();
                return filtered.size() > clamped ? filtered.subList(0, clamped) : filtered;
            }

            @Override
            public void record(
                    final String action,
                    final String actor,
                    final String subject,
                    final UUID mcUuid,
                    final String detail) {
                throw new UnsupportedOperationException("not exercised by this fake");
            }
        };
    }
}
