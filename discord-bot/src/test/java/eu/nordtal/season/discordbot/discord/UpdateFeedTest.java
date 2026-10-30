package eu.nordtal.season.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.RunRecord;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class UpdateFeedTest {

    private static final Instant NOW = Instant.parse("2026-09-08T20:00:00Z");

    private static final class Board implements UpdateFeed.Board {

        record Post(DocumentRenderer.Drawn card, String messageId) {}

        final List<Post> posted = new ArrayList<>();
        final List<Post> edited = new ArrayList<>();

        /** Whether Discord acknowledges a post. False tests the state with no message id yet. */
        boolean acknowledge = true;

        /** Runs inside the next post and stands in for a signal that arrives during a pass. */
        Runnable duringPost = () -> {};

        @Override
        public void post(final DocumentRenderer.Drawn card, final Consumer<String> sentId) {
            final Runnable during = duringPost;
            duringPost = () -> {};
            during.run();
            final String id = "msg-" + (posted.size() + 1);
            posted.add(new Post(card, id));
            if (acknowledge) {
                sentId.accept(id);
            }
        }

        @Override
        public void edit(final String messageId, final DocumentRenderer.Drawn card) {
            edited.add(new Post(card, messageId));
        }
    }

    private static final class Rows implements UpdateDirectory {

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

    private final Rows rows = new Rows();
    private final Board board = new Board();
    private final DocumentRenderer documents = StoredDocumentsStandIn.renderer();
    private final UpdateFeed feed = new UpdateFeed(rows, board, documents, "https://steward.example/");

    @Test
    void aPassIsAdmittedBeforeTheHandOverSoABusyPoolQueuesOneAndNotThirty() {
        // The executor stands in for busy workers, holding the task before it runs.
        final List<Runnable> queued = new ArrayList<>();
        final java.util.concurrent.Executor busy = queued::add;

        rows.put(row(1, UpdateStatus.RUNNING, RunStage.STOPPING, null));

        feed.submit(busy);
        feed.submit(busy);
        feed.submit(busy);
        assertEquals(1, queued.size(), "three ticks against a busy pool queued more than one pass");

        queued.getFirst().run();
        assertEquals(1, board.posted.size(), "the one pass that was admitted did not run");

        feed.submit(busy);
        assertEquals(2, queued.size(), "the flag was not released when the pass finished");
    }

    @Test
    void aSignalDuringAPassIsFollowedByOneMorePassSoTheChangeItAnnouncedIsDrawn() {
        final List<Runnable> queued = new ArrayList<>();
        final java.util.concurrent.Executor busy = queued::add;
        rows.put(row(1, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        // The run moves on while the first pass draws it, and the signal of that write arrives during the pass.
        board.duringPost = () -> {
            rows.put(row(2, UpdateStatus.RUNNING, RunStage.STOPPING, null));
            feed.submit(busy);
        };

        feed.submit(busy);
        while (!queued.isEmpty()) {
            queued.removeFirst().run();
        }

        assertEquals(2, board.posted.size(), "the row written during the pass waited for the next signal");
    }

    @Test
    void aPoolThatRefusesThePassReleasesTheFlagInsteadOfSwitchingTheFeedOff() {
        final java.util.concurrent.Executor shuttingDown = task -> {
            throw new java.util.concurrent.RejectedExecutionException("shutting down");
        };
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> feed.submit(shuttingDown));

        // Without the release in the catch block, one rejection silences the feed for good.
        rows.put(row(1, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        final List<Runnable> queued = new ArrayList<>();
        feed.submit(queued::add);
        assertEquals(1, queued.size(), "the flag stayed taken after a rejected submission");
    }

    @Test
    void aCancelledRunIsStillALine() {
        rows.put(row(2, UpdateStatus.CANCELLED, RunStage.RESOLVING, NOW));
        feed.tick();

        assertEquals(1, board.posted.size(), "a cancelled run is still worth a line in the channel");
    }

    private static final Actor OWNER = Actor.person(eu.nordtal.season.common.id.DiscordId.of("300000000000000077"));

    private static UpdateRequest row(
            final long id,
            final UpdateStatus status,
            final @Nullable RunStage stage,
            final @Nullable Instant finished) {
        return new UpdateRequest(id, UpdateKind.UPDATE, status, OWNER, NOW, NOW, null, List.of(), NOW, finished, stage);
    }

    /** A step of a run, with the artefacts it names. */
    private static RunStep.Row step(
            final RunStep.Kind kind,
            final @Nullable String service,
            final RunStep.@Nullable Outcome outcome,
            final RunStep.@Nullable Reason reason,
            final RunStep.Change... changes) {
        return new RunStep.Row(
                0,
                kind,
                service,
                null,
                null,
                outcome,
                reason,
                null,
                List.of(changes),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    private static DocumentRenderer.Drawn card(
            final UpdateRequest request, final DocumentRenderer documents, final RunStep.Row... steps) {
        return UpdateFeed.card(new RunRecord(request.stage(), List.of(steps)), request, documents, false, null);
    }

    @Test
    void aRunStartedInGameIsPostedAndEditedAsItMoves() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.PLANNED, null));
        feed.tick();

        assertEquals(1, board.posted.size());
        assertTrue(
                lines(board.posted.getFirst().card()).contains("**By** <@300000000000000077>"),
                "who asked is a line of its own, as a mention");

        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();
        assertEquals(1, board.posted.size(), "the same run, not a second message");
        assertEquals(1, board.edited.size());
        assertEquals("msg-1", board.edited.getFirst().messageId());

        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        assertEquals(2, board.edited.size(), "and the last edit is the answer");

        feed.tick();
        assertEquals(2, board.edited.size(), "a settled run is not followed any more");
    }

    @Test
    void anUnchangedRunIsNotReSentBecauseDiscordRateLimitsEdits() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.INSTALLING, null));
        feed.tick();
        feed.tick();
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(
                List.of(),
                board.edited,
                "this polls every two seconds and a run writes a stage at a time; twenty identical"
                        + " edits between two stages would spend the edit budget on nothing");
    }

    @Test
    void aRestartBeginsAtTheNewestRowSoASeasonOfHistoryIsNotRePosted() {
        for (long id = 1; id <= 40; id++) {
            rows.put(row(id, UpdateStatus.DONE, RunStage.DONE, NOW.minus(Duration.ofDays(3))));
        }
        feed.start();
        feed.tick();

        assertEquals(List.of(), board.posted);
    }

    @Test
    void aRunThatEndedWhileTheBotWasDownIsPostedOnceAsAResult() {
        // A row below max(id) is one that the feed never looks at again.
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW.minus(Duration.ofMinutes(4))));
        rows.put(row(2L, UpdateStatus.DONE, RunStage.DONE, NOW.minus(Duration.ofHours(9))));
        feed.start();

        assertEquals(1, board.posted.size(), "and nothing older than the catch-up window");
        feed.tick();
        assertEquals(1, board.posted.size(), "posted once; there is nothing left to edit into it");
    }

    @Test
    void aPostDiscordNeverAcknowledgedIsNotEditedAgainstAMessageIdThatIsNotThere() {
        board.acknowledge = false;
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.RESOLVING, null));
        feed.tick();

        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();

        assertEquals(
                List.of(),
                board.edited,
                "the message id arrives on a JDA thread and may never arrive at all; the row is the"
                        + " record and the card is only a drawing of it");
    }

    @Test
    void aFailedRunsCardIsHandedOnceToTheAlertThatTakesItsPlace() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        rows.put(row(2L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        rows.put(row(3L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        rows.put(row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        feed.tick();

        assertEquals("msg-1", feed.failedCard(1L), "failed while it was followed");
        assertEquals("msg-2", feed.failedCard(2L), "failed before the feed saw it");
        assertNull(feed.failedCard(1L), "handed over once");
        assertNull(feed.failedCard(3L), "a run that went well keeps its card");
    }

    @Test
    void aRunAlreadyFinishedWhenTheFeedFirstSeesItIsPostedOnceAndNotFollowed() {
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(List.of(), board.edited);
    }

    @Test
    void everyStageCarriesTheSameColourAndItsOutcomeAsAnEmoji() {
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        rows.steps.put(
                1L,
                List.of(
                        step(RunStep.Kind.VERIFY, "smp", RunStep.Outcome.DONE, null),
                        step(RunStep.Kind.STOP, "limbo", RunStep.Outcome.SKIPPED, RunStep.Reason.HELD)));
        rows.put(row(2L, UpdateStatus.FAILED, RunStage.FAILED, NOW));
        rows.steps.put(
                2L, List.of(step(RunStep.Kind.VERIFY, "smp", RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY)));
        feed.tick();

        final DocumentRenderer.Drawn good = board.posted.get(0).card();
        final DocumentRenderer.Drawn bad = board.posted.get(1).card();
        assertEquals(accent(good), accent(bad), "a finished and a failed run carry the same line");
        assertTrue(title(good).startsWith("✅"), title(good));
        assertTrue(title(bad).startsWith("🛑"), title(bad));
        final String goodLines = lines(good);
        assertTrue(goodLines.contains("✅ **smp**"), goodLines);
        assertTrue(goodLines.contains("➖ **limbo**"), goodLines);
        for (final String symbol : List.of("✔", "✖", "○", "◑", "\u2013")) {
            assertTrue(!goodLines.contains(symbol), "a text symbol beside emojis: " + symbol);
        }
    }

    @Test
    void aServicesLineIsItsWorstStepItsLatestStepAndTheArtefactsOfItsLatestStepThatHasAny() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.DONE, RunStage.DONE, NOW),
                documents,
                step(
                        RunStep.Kind.INSTALL,
                        "smp",
                        RunStep.Outcome.DONE,
                        null,
                        new RunStep.Change("paper", "133", "134", null),
                        new RunStep.Change("image", null, null, RunStep.Image.PULLED)),
                step(RunStep.Kind.VERIFY, "smp", RunStep.Outcome.DONE, null),
                step(
                        RunStep.Kind.INSTALL,
                        "proxy",
                        RunStep.Outcome.DONE,
                        null,
                        new RunStep.Change(
                                "resource-pack", "f8fb6640b394ffeea812d02b8c4b3d4cdfe956a9", "0.24.0", null)),
                step(RunStep.Kind.VERIFY, "proxy", RunStep.Outcome.WARNING, RunStep.Reason.NOT_HEALTHY));

        final String shown = field(card, "Services");
        assertTrue(shown.contains("✅ **smp** *Verify* paper 133 → 134"), shown);
        assertTrue(shown.contains("image pulled"), shown);
        assertTrue(
                shown.contains("⚠️ **proxy** *Verify* resource-pack f8fb6640 → 0.24.0"),
                "a pack's hash is short: " + shown);
        assertFalse(shown.contains("run."), "no key is ever shown in place of its words: " + shown);
    }

    @Test
    void aStepStillRunningMarksItsServiceAsWorking() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null),
                documents,
                step(RunStep.Kind.STOP, "smp", null, null));

        assertTrue(field(card, "Services").startsWith("🔄 **smp** *Stop*"), field(card, "Services"));
    }

    @Test
    void aSuccessfulRunShowsNoReasonsWhateverItsStepsSay() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.DONE, RunStage.DONE, NOW),
                documents,
                step(RunStep.Kind.STANDBY_UP, "limbo-standby", RunStep.Outcome.DONE, null),
                step(RunStep.Kind.PLAYERS_MOVED, null, RunStep.Outcome.WARNING, RunStep.Reason.PLAYERS_UNKNOWN),
                step(RunStep.Kind.STOP, "smp", RunStep.Outcome.WARNING, RunStep.Reason.UNVERIFIED_STOP),
                step(RunStep.Kind.OFFSITE_COPY, null, RunStep.Outcome.SKIPPED, RunStep.Reason.NO_REPOSITORY));

        assertEquals(List.of("Duration", "Services"), names(card), "a run that went well is its lines");
    }

    @Test
    void aMissingSecretIsSaidUnderItsServiceEvenInARunThatWentWell() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.DONE, RunStage.DONE, NOW),
                documents,
                step(RunStep.Kind.RESOLVE, "steward", RunStep.Outcome.WARNING, RunStep.Reason.LOCAL_BUILD),
                step(RunStep.Kind.RESOLVE, "steward", RunStep.Outcome.WARNING, RunStep.Reason.MISSING_SECRET));

        assertEquals(List.of("Duration", "Services", "Why"), names(card));
        assertEquals(
                "**steward** a secret is missing, ./nordtal.sh update on the host adds it",
                field(card, "Why"),
                "only the host adds it, so the card says how; the local build stays a mark");
    }

    @Test
    void aFailedRunShowsOnlyWhatFailedItEachUnderItsService() {
        final DocumentRenderer.Drawn card = card(
                row(1L, UpdateStatus.FAILED, RunStage.FAILED, NOW),
                documents,
                step(RunStep.Kind.STANDBY_UP, "limbo-standby", RunStep.Outcome.DONE, null),
                step(RunStep.Kind.PLAYERS_MOVED, null, RunStep.Outcome.WARNING, RunStep.Reason.PLAYERS_UNKNOWN),
                step(RunStep.Kind.STOP, "smp", RunStep.Outcome.FAILED, RunStep.Reason.REFUSED),
                step(RunStep.Kind.STOP, "hunger_games", RunStep.Outcome.FAILED, RunStep.Reason.NO_CONTAINER),
                step(RunStep.Kind.CLEANUP, null, RunStep.Outcome.FAILED, RunStep.Reason.UNEXPECTED));

        assertEquals(List.of("Duration", "Services", "Why"), names(card));
        assertEquals("""
                **smp** refused, the run log has the answer
                **hunger\\_games** no container
                steward-agent failed, its log says how""", field(card, "Why"), "the failed steps in order; nothing that went right and no warning");
    }

    @Test
    void aStoppedRunIsItsTitleAndARunInFlightSaysWhyOnlyOnceItHasFailed() {
        final DocumentRenderer.Drawn stopped = card(
                row(1L, UpdateStatus.CANCELLED, RunStage.CANCELLED, NOW),
                documents,
                step(RunStep.Kind.COUNTDOWN, null, RunStep.Outcome.SKIPPED, RunStep.Reason.CANCELLED));
        final DocumentRenderer.Drawn running = card(
                row(2L, UpdateStatus.RUNNING, RunStage.VERIFYING, null),
                documents,
                step(RunStep.Kind.STOP, "smp", RunStep.Outcome.FAILED, RunStep.Reason.REFUSED),
                step(RunStep.Kind.STANDBY_UP, "limbo-standby", RunStep.Outcome.FAILED, RunStep.Reason.NO_CONTAINER));

        assertFalse(names(stopped).contains("Why"), names(stopped).toString());
        assertFalse(names(running).contains("Why"), "the card is edited once the run settles: " + names(running));
    }

    @Test
    void theTitleNamesTheRunsKindAtEachStage() {
        assertEquals("✅ Backup finished", title(UpdateKind.BACKUP, RunStage.DONE));
        assertEquals("🛑 The backup failed", title(UpdateKind.BACKUP, RunStage.FAILED));
        assertEquals("✅ Update finished", title(UpdateKind.UPDATE, RunStage.DONE));
        assertEquals("⏳ Restarting shortly", title(UpdateKind.RESTART, RunStage.COUNTDOWN));
        assertEquals("🔄 Stopping the servers for the take-down", title(UpdateKind.DOWN, RunStage.STOPPING));
        assertEquals("✅ Nothing to start", title(UpdateKind.START, RunStage.NOTHING_TO_DO));
        assertEquals("⏹️ Plugin removal cancelled", title(UpdateKind.REMOVE_PLUGIN, RunStage.CANCELLED));
        for (final UpdateKind kind : UpdateKind.values()) {
            for (final RunStage stage : RunStage.values()) {
                final String shown = title(kind, stage);
                assertFalse(shown.contains("{") || shown.contains("run."), "unrendered: " + shown);
            }
        }
    }

    @Test
    void theKindIsInTheTitleAndNotAgainAsAField() {
        rows.put(new UpdateRequest(
                1L, UpdateKind.BACKUP, UpdateStatus.DONE, OWNER, NOW, NOW, null, List.of(), NOW, NOW, RunStage.DONE));
        feed.tick();

        assertEquals(List.of("By", "Duration"), names(board.posted.getFirst().card()));
    }

    @Test
    void theCardLinksToTheRunsPageInStewardWhileItMovesAndOnceItIsDone() {
        rows.put(row(7L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();
        rows.put(row(7L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();

        final Button posted =
                java.util.Objects.requireNonNull(link(board.posted.getFirst().card()));
        assertEquals("https://steward.example/operations/updates/7", posted.getUrl());
        assertEquals("Open in Steward", posted.getLabel());
        assertEquals(
                posted.getUrl(),
                java.util.Objects.requireNonNull(link(board.edited.getFirst().card()))
                        .getUrl(),
                "an edit keeps the link");
    }

    @Test
    void withoutStewardsAddressTheCardCarriesNoLink() {
        final UpdateFeed unlinked = new UpdateFeed(rows, board, documents, "");
        rows.put(row(7L, UpdateStatus.DONE, RunStage.DONE, NOW));
        unlinked.tick();

        assertEquals(null, link(board.posted.getFirst().card()));
    }

    @Test
    void aStartUpInstallThatWentWellIsNotPosted() {
        feed.start();
        rows.put(install(1L, UpdateStatus.DONE, RunStage.DONE));
        feed.tick();

        assertEquals(List.of(), board.posted, "the admins hear of a start-up install only when it failed");
    }

    @Test
    void aStartUpInstallThatFailedIsPostedUnderItsOwnTitle() {
        feed.start();
        rows.put(install(1L, UpdateStatus.FAILED, RunStage.FAILED));
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(
                "🛑 The start-up install failed", title(board.posted.getFirst().card()));
    }

    @Test
    void aStartUpInstallThatWentWellWhileTheBotWasDownIsNotCaughtUp() {
        rows.put(install(1L, UpdateStatus.DONE, RunStage.DONE));
        rows.put(install(2L, UpdateStatus.FAILED, RunStage.FAILED));
        feed.start();

        assertEquals(1, board.posted.size(), "only the failed one is caught up");
    }

    @Test
    void anUpdateThatFoundNothingToDoIsNotPosted() {
        feed.start();
        rows.put(row(1L, UpdateStatus.PENDING, null, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.RESOLVING, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.DONE, RunStage.NOTHING_TO_DO, NOW));
        feed.tick();
        feed.tick();

        assertEquals(List.of(), board.posted, "the check after a real update says nothing new");
    }

    @Test
    void anUpdateThatFoundNothingToDoWhileTheBotWasDownIsNotCaughtUp() {
        rows.put(row(1L, UpdateStatus.DONE, RunStage.NOTHING_TO_DO, NOW));
        feed.start();

        assertEquals(List.of(), board.posted);
    }

    @Test
    void anUpdateIsPostedOnceItHasAPlanAndFollowedFromThere() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.RESOLVING, null));
        feed.tick();
        assertEquals(List.of(), board.posted, "while it resolves, it may still find nothing to do");

        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.COUNTDOWN, null));
        feed.tick();
        assertEquals(1, board.posted.size());

        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        assertEquals(1, board.posted.size(), "the same run, not a second message");
        assertEquals(1, board.edited.size());
    }

    @Test
    void aRestartThatFoundNothingToDoKeepsItsCardBecauseSomeoneAskedForIt() {
        rows.put(new UpdateRequest(
                1L,
                UpdateKind.RESTART,
                UpdateStatus.DONE,
                OWNER,
                NOW,
                NOW,
                null,
                List.of(),
                NOW,
                NOW,
                RunStage.NOTHING_TO_DO));
        feed.tick();

        assertEquals(1, board.posted.size());
    }

    private static UpdateRequest install(final long id, final UpdateStatus status, final RunStage stage) {
        return new UpdateRequest(
                id, UpdateKind.INSTALL, status, Actor.STEWARD, NOW, NOW, null, List.of(), NOW, NOW, stage);
    }

    private String title(final UpdateKind kind, final RunStage stage) {
        return title(card(
                new UpdateRequest(1L, kind, UpdateStatus.RUNNING, OWNER, NOW, NOW, null, List.of(), NOW, null, stage),
                documents));
    }

    private static String title(final DocumentRenderer.Drawn card) {
        return card.text().lines().findFirst().orElseThrow().replaceFirst("^## ", "");
    }

    /** Returns the sections of the card in order: the by and duration lines, then each heading. */
    private static List<String> names(final DocumentRenderer.Drawn card) {
        return card.text()
                .lines()
                .map(line -> line.startsWith("**By**")
                        ? "By"
                        : line.startsWith("**Duration**")
                                ? "Duration"
                                : line.startsWith("### ") ? line.substring(4) : null)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String field(final DocumentRenderer.Drawn card, final String name) {
        final List<String> all = card.text().lines().toList();
        final int heading = all.indexOf("### " + name);
        if (heading < 0) {
            throw new AssertionError("no heading " + name + " in " + names(card));
        }
        return all.subList(heading + 1, all.size()).stream()
                .takeWhile(line -> !line.startsWith("### "))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String lines(final DocumentRenderer.Drawn card) {
        return card.text();
    }

    private static @Nullable Integer accent(final DocumentRenderer.Drawn card) {
        return MessageComponentTree.of(card.components())
                .findAll(net.dv8tion.jda.api.components.container.Container.class)
                .getFirst()
                .getAccentColorRaw();
    }

    private static @Nullable Button link(final DocumentRenderer.Drawn card) {
        return MessageComponentTree.of(card.components()).findAll(Button.class).stream()
                .filter(button -> button.getStyle() == ButtonStyle.LINK)
                .findFirst()
                .orElse(null);
    }
}
