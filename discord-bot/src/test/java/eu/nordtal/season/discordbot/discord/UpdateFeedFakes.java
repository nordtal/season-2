package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.RunRecord;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** The admin channel and the run rows that {@link UpdateFeedTest} hands the feed. */
final class UpdateFeedFakes {

    static final Instant NOW = Instant.parse("2026-09-08T20:00:00Z");

    private UpdateFeedFakes() {}

    static final class Board implements UpdateFeed.Board {

        record Post(DocumentRenderer.Drawn card, String messageId) {}

        final List<Post> posted = new ArrayList<>();
        final List<Post> edited = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();

        /** Whether Discord acknowledges a post. False tests the state with no message id yet. */
        boolean acknowledge = true;

        /** Acknowledgements held back while {@link #late} is set; running one stands in for Discord's answer. */
        final List<Runnable> unanswered = new ArrayList<>();

        /** Whether Discord answers a post only after the pass that sent it. */
        boolean late;

        /** Runs inside the next post and stands in for a signal that arrives during a pass. */
        Runnable duringPost = () -> {};

        @Override
        public void post(final DocumentRenderer.Drawn card, final Consumer<String> sentId) {
            final Runnable during = duringPost;
            duringPost = () -> {};
            during.run();
            final String id = "msg-" + (posted.size() + 1);
            posted.add(new Post(card, id));
            if (late) {
                unanswered.add(() -> sentId.accept(id));
            } else if (acknowledge) {
                sentId.accept(id);
            }
        }

        @Override
        public void edit(final String messageId, final DocumentRenderer.Drawn card) {
            edited.add(new Post(card, messageId));
        }

        @Override
        public void delete(final String messageId) {
            deleted.add(messageId);
        }
    }

    static final class Rows implements UpdateDirectory {

        @Override
        public java.util.List<UpdateRequest> recent(final int limit) {
            return java.util.List.of();
        }

        final Map<Long, UpdateRequest> byId = new LinkedHashMap<>();

        /** The steps of each run, by request id. */
        final Map<Long, List<RunStep.Row>> steps = new LinkedHashMap<>();

        @Override
        public RunRecord recordOf(final long run) {
            final UpdateRequest request = byId.get(run);
            return new RunRecord(request == null ? null : request.stage(), steps.getOrDefault(run, List.of()));
        }

        @Override
        public java.util.Optional<UpdateRequest> running() {
            return java.util.Optional.empty();
        }

        void put(final UpdateRequest request) {
            byId.put(request.id(), request);
        }

        @Override
        public List<UpdateRequest> since(final long id) {
            return byId.values().stream().filter(row -> row.id() > id).toList();
        }

        @Override
        public long latestId() {
            return byId.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
        }

        @Override
        public List<UpdateRequest> finishedWithin(final Duration window) {
            final Instant from = NOW.minus(window);
            return byId.values().stream()
                    .filter(row -> row.finished() != null && row.finished().isAfter(from))
                    .toList();
        }

        @Override
        public Optional<UpdateRequest> find(final long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<UpdateRequest> lastSuccessfulBackup(final Duration within) {
            throw new UnsupportedOperationException("the feed never asks about backups");
        }

        @Override
        public UpdateRequest submit(
                final eu.nordtal.season.database.inbox.StewardRequest request,
                final java.util.@Nullable List<String> services,
                final Actor actor,
                final @Nullable Duration delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> claimNext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> finish(final long id, final RunStage stage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean progress(final long id, final RunStage stage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> startCountdown(
                final long id, final Duration seconds, final java.util.Collection<String> moving) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean commitCountdown(final long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> countingDown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> cancelCountdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> nextDue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int settleOrphans(final java.util.function.Predicate<String> stillRunning) {
            throw new UnsupportedOperationException();
        }
    }
}
