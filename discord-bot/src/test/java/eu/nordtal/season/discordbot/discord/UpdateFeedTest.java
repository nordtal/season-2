package eu.nordtal.season.discordbot.discord;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.StoredLayoutsStandIn;
import eu.nordtal.season.messages.Messages;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

        record Post(LayoutRenderer.Drawn card, String messageId) {}

        final List<Post> posted = new ArrayList<>();
        final List<Post> edited = new ArrayList<>();

        /** Whether Discord acknowledges a post. False tests the state with no message id yet. */
        boolean acknowledge = true;

        /** Runs inside the next post and stands in for a signal that arrives during a pass. */
        Runnable duringPost = () -> {};

        @Override
        public void post(final LayoutRenderer.Drawn card, final Consumer<String> sentId) {
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
        public void edit(final String messageId, final LayoutRenderer.Drawn card) {
            edited.add(new Post(card, messageId));
        }
    }

    private static final class Rows implements UpdateDirectory {

        @Override
        public java.util.List<UpdateRequest> recent(final int limit) {
            return java.util.List.of();
        }

        final Map<Long, UpdateRequest> byId = new LinkedHashMap<>();

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
                final Actor actor,
                final Duration delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> claimNext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> finish(final long id, final UpdateStatus status, final String result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean progress(final long id, final String result) {
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
        public int settleOrphans(
                final eu.nordtal.season.messages.MessageRef why,
                final java.util.function.Predicate<String> stillRunning) {
            throw new UnsupportedOperationException();
        }
    }

    private final Rows rows = new Rows();
    private final Board board = new Board();
    private final LayoutRenderer layouts = new LayoutRenderer(
            DiscordRenderer.of(Messages.load(
                    UpdateFeedTest.class.getClassLoader(),
                    List.of("messages/access", "messages/admin"),
                    Locale.ENGLISH)),
            StoredLayoutsStandIn.none());
    private final UpdateFeed feed = new UpdateFeed(rows, board, layouts, "https://steward.example/");

    @Test
    void aPassIsAdmittedBeforeTheHandOverSoABusyPoolQueuesOneAndNotThirty() {
        // The executor stands in for busy workers, holding the task before it runs.
        final List<Runnable> queued = new ArrayList<>();
        final java.util.concurrent.Executor busy = queued::add;

        rows.put(row(1, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));

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
        rows.put(row(1, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
        // The run moves on while the first pass draws it, and the signal of that write arrives during the pass.
        board.duringPost = () -> {
            rows.put(row(2, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
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
        rows.put(row(1, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
        final List<Runnable> queued = new ArrayList<>();
        feed.submit(queued::add);
        assertEquals(1, queued.size(), "the flag stayed taken after a rejected submission");
    }

    @Test
    void aCancelledRunIsStillALine() {
        rows.put(row(2, UpdateStatus.CANCELLED, reportAt(UpdateReport.Stage.RESOLVING), NOW));
        feed.tick();

        assertEquals(1, board.posted.size(), "a cancelled run is still worth a line in the channel");
    }

    private static final Actor OWNER = Actor.person(eu.nordtal.season.common.id.DiscordId.of("300000000000000077"));

    private static UpdateRequest row(
            final long id, final UpdateStatus status, final String result, final Instant finished) {
        return new UpdateRequest(
                id, UpdateKind.UPDATE, status, OWNER, NOW, NOW, null, List.of(), NOW, finished, result);
    }

    private static String reportAt(final UpdateReport.Stage stage) {
        return UpdateReports.toJson(UpdateReport.at(stage));
    }

    @Test
    void aRunStartedInGameIsPostedAndEditedAsItMoves() {
        rows.put(row(1L, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.RESOLVING), null));
        feed.tick();

        assertEquals(1, board.posted.size());
        assertTrue(
                lines(board.posted.getFirst().card()).contains("**By** <@300000000000000077>"),
                "who asked is a line of its own, as a mention");

        rows.put(row(1L, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
        feed.tick();
        assertEquals(1, board.posted.size(), "the same run, not a second message");
        assertEquals(1, board.edited.size());
        assertEquals("msg-1", board.edited.getFirst().messageId());

        rows.put(row(1L, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
        feed.tick();
        assertEquals(2, board.edited.size(), "and the last edit is the answer");

        feed.tick();
        assertEquals(2, board.edited.size(), "a settled run is not followed any more");
    }

    @Test
    void anUnchangedReportIsNotReSentBecauseDiscordRateLimitsEdits() {
        rows.put(row(1L, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.INSTALLING), null));
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
            rows.put(row(id, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW.minus(Duration.ofDays(3))));
        }
        feed.start();
        feed.tick();

        assertEquals(List.of(), board.posted);
    }

    @Test
    void aRunThatEndedWhileTheBotWasDownIsPostedOnceAsAResult() {
        // A row below max(id) is one that the feed never looks at again.
        rows.put(row(1L, UpdateStatus.FAILED, reportAt(UpdateReport.Stage.FAILED), NOW.minus(Duration.ofMinutes(4))));
        rows.put(row(2L, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW.minus(Duration.ofHours(9))));
        feed.start();

        assertEquals(1, board.posted.size(), "and nothing older than the catch-up window");
        feed.tick();
        assertEquals(1, board.posted.size(), "posted once; there is nothing left to edit into it");
    }

    @Test
    void aPostDiscordNeverAcknowledgedIsNotEditedAgainstAMessageIdThatIsNotThere() {
        board.acknowledge = false;
        rows.put(row(1L, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.RESOLVING), null));
        feed.tick();

        rows.put(row(1L, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
        feed.tick();

        assertEquals(
                List.of(),
                board.edited,
                "the message id arrives on a JDA thread and may never arrive at all; the row is the"
                        + " record and the card is only a drawing of it");
    }

    @Test
    void aRunAlreadyFinishedWhenTheFeedFirstSeesItIsPostedOnceAndNotFollowed() {
        rows.put(row(1L, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
        feed.tick();
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(List.of(), board.edited);
    }

    @Test
    void everyStageCarriesTheSameColourAndItsOutcomeAsAnEmoji() {
        final UpdateReport done = new UpdateReport(
                UpdateReport.Stage.DONE,
                List.of(
                        new UpdateReport.ServiceLine("smp", UpdateReport.State.HEALTHY, List.of(), null),
                        new UpdateReport.ServiceLine("limbo", UpdateReport.State.UNCHANGED, List.of(), null)),
                List.of());
        final UpdateReport failed = new UpdateReport(
                UpdateReport.Stage.FAILED,
                List.of(new UpdateReport.ServiceLine("smp", UpdateReport.State.FAILED, List.of(), null)),
                List.of());
        rows.put(row(1L, UpdateStatus.DONE, UpdateReports.toJson(done), NOW));
        rows.put(row(2L, UpdateStatus.FAILED, UpdateReports.toJson(failed), NOW));
        feed.tick();

        final LayoutRenderer.Drawn good = board.posted.get(0).card();
        final LayoutRenderer.Drawn bad = board.posted.get(1).card();
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
    void aChangeToldInAMessageIsRenderedAndOneStoredInWordsStillReads() {
        final UpdateReport told = new UpdateReport(
                UpdateReport.Stage.DONE,
                List.of(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.HEALTHY,
                        List.of(UpdateReport.Change.told("image", TEXTS.report().imageOutdated())),
                        null)),
                List.of());
        // An image change as the inbox keeps it from before it was a message.
        final String stored = "{\"stage\": \"DONE\", \"notes\": [], \"services\": [{\"state\": \"HEALTHY\","
                + " \"changes\": [{\"to\": \"out of date\", \"state\": \"MOVING\", \"artefact\": \"image\"}],"
                + " \"service\": \"limbo\"}]}";
        rows.put(row(1L, UpdateStatus.DONE, UpdateReports.toJson(told), NOW));
        rows.put(row(2L, UpdateStatus.DONE, stored, NOW));
        feed.tick();

        final String now = lines(board.posted.get(0).card());
        final String before = lines(board.posted.get(1).card());
        assertTrue(now.contains("image out of date"), now);
        assertFalse(now.contains("report.image-outdated"), now);
        assertFalse(now.contains("**-**"), "the placeholder of a version is never shown: " + now);
        assertTrue(before.contains("image out of date"), before);
    }

    @Test
    void aServicesChangesFollowItOnItsLineAndAPacksHashIsShort() {
        final UpdateReport done = new UpdateReport(
                UpdateReport.Stage.DONE,
                List.of(
                        new UpdateReport.ServiceLine(
                                "smp",
                                UpdateReport.State.HEALTHY,
                                List.of(
                                        new UpdateReport.Change("paper", "133", "134"),
                                        UpdateReport.Change.told(
                                                "image", TEXTS.report().imageRenewed())),
                                null),
                        new UpdateReport.ServiceLine(
                                "proxy",
                                UpdateReport.State.HEALTHY,
                                List.of(new UpdateReport.Change(
                                        "resource-pack", "f8fb6640b394ffeea812d02b8c4b3d4cdfe956a9", "0.24.0")),
                                null)),
                List.of());
        rows.put(row(1L, UpdateStatus.DONE, UpdateReports.toJson(done), NOW));
        feed.tick();

        final String shown = lines(board.posted.get(0).card());
        assertTrue(shown.contains("✅ **smp** *running* paper 133 → 134 and image updated\n"), shown);
        assertTrue(shown.contains("✅ **proxy** *running* resource-pack f8fb664 → 0.24.0"), shown);
    }

    @Test
    void aBackupsSizeAndTimeAreRenderedFromTheirValues() {
        final UpdateReport saved = new UpdateReport(
                UpdateReport.Stage.DONE,
                List.of(new UpdateReport.ServiceLine(
                        "nordtal-s2_mc-smp",
                        UpdateReport.State.SAVED,
                        List.of(UpdateReport.Change.told(
                                "backup",
                                TEXTS.report().saved(ByteSize.of(1_234_567).message(), Duration.ofSeconds(12)))),
                        null)),
                List.of());
        rows.put(row(1L, UpdateStatus.DONE, UpdateReports.toJson(saved), NOW));
        feed.tick();

        final String shown = lines(board.posted.getFirst().card());
        assertTrue(shown.contains("backup 1.2 MiB in 12s"), shown);
        assertFalse(shown.contains("report."), "no key is ever shown in place of its words: " + shown);
    }

    @Test
    void aSuccessfulRunShowsNoNotesWhateverItsRecordsSay() {
        final UpdateReport done = UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.HEALTHY,
                        List.of(),
                        UpdateReport.Facts.refused(UpdateReport.Act.STOP, "came back slowly")))
                .withNotes(UpdateReport.Note.done(
                                UpdateReport.Step.STANDBY, TEXTS.report().standbysReady())
                        .each(List.of("limbo-standby")))
                .withNote(UpdateReport.Note.warning(
                                UpdateReport.Step.PLAYERS, TEXTS.report().playersUnknown(10))
                        .on("smp"))
                .withNote(UpdateReport.Note.skipped(
                        UpdateReport.Step.BACKUP, TEXTS.report().noOffsite()));

        final LayoutRenderer.Drawn card =
                UpdateFeed.card(done, row(1, UpdateStatus.DONE, null, NOW), layouts, false, null);

        assertEquals(List.of("Duration", "Services"), names(card), "a run that went well is its lines");
        assertFalse(lines(card).contains("came back slowly"), lines(card));
    }

    @Test
    void aFailedRunShowsOnlyWhatFailedItEachUnderItsService() {
        final UpdateReport failed = UpdateReport.at(UpdateReport.Stage.FAILED)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.FAILED,
                        List.of(),
                        UpdateReport.Facts.refused(UpdateReport.Act.STOP, "exit *1*")))
                .with(new UpdateReport.ServiceLine(
                        "limbo",
                        UpdateReport.State.HEALTHY,
                        List.of(),
                        UpdateReport.Facts.refused(UpdateReport.Act.STOP, "came back slowly")))
                .withNotes(UpdateReport.Note.done(
                                UpdateReport.Step.STANDBY, TEXTS.report().standbysReady())
                        .each(List.of("limbo-standby")))
                .withNote(UpdateReport.Note.warning(
                                UpdateReport.Step.PLAYERS, TEXTS.report().playersUnknown(10))
                        .on("smp"))
                .withNotes(UpdateReport.Note.failed(
                                UpdateReport.Step.STOP, TEXTS.report().notStopped(UpdateReport.Undertaking.INSTALL))
                        .each(List.of("hunger_games")))
                .withNote(UpdateReport.Note.failed(
                        UpdateReport.Step.RUN, TEXTS.report().failedUnexpectedly("boom")));

        final LayoutRenderer.Drawn card =
                UpdateFeed.card(failed, row(1, UpdateStatus.FAILED, null, NOW), layouts, false, null);

        assertEquals(List.of("Duration", "Services", "Why"), names(card));
        assertEquals(
                """
                **smp** could not be stopped: exit \\*1\\*
                **hunger\\_games** not stopped, so nothing was installed, the rest started again
                failed: boom""",
                field(card, "Why"),
                "the failed lines first, then the failed records; nothing that went right and no warning");
    }

    @Test
    void aStoppedRunIsItsTitleAndARunInFlightSaysWhyOnlyOnceItHasFailed() {
        final UpdateReport cancelled = UpdateReport.at(UpdateReport.Stage.CANCELLED)
                .withNote(UpdateReport.Note.skipped(
                        UpdateReport.Step.RUN, TEXTS.report().cancelled()));
        final UpdateReport going = UpdateReport.at(UpdateReport.Stage.VERIFYING)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.FAILED,
                        List.of(),
                        UpdateReport.Facts.refused(UpdateReport.Act.STOP, "exit 1")))
                .withNote(UpdateReport.Note.failed(
                                UpdateReport.Step.STANDBY, TEXTS.report().standbyNoContainer())
                        .on("limbo-standby"));

        final LayoutRenderer.Drawn stopped =
                UpdateFeed.card(cancelled, row(1, UpdateStatus.CANCELLED, null, NOW), layouts, false, null);
        final LayoutRenderer.Drawn running =
                UpdateFeed.card(going, row(2, UpdateStatus.RUNNING, null, null), layouts, false, null);

        assertFalse(names(stopped).contains("Why"), names(stopped).toString());
        assertFalse(names(running).contains("Why"), "the card is edited once the run settles: " + names(running));
    }

    @Test
    void theTitleNamesTheRunsKindAtEachStage() {
        assertEquals("✅ Backup finished", title(UpdateKind.BACKUP, UpdateReport.Stage.DONE));
        assertEquals("🛑 The backup failed", title(UpdateKind.BACKUP, UpdateReport.Stage.FAILED));
        assertEquals("✅ Update finished", title(UpdateKind.UPDATE, UpdateReport.Stage.DONE));
        assertEquals("⏳ Restarting shortly", title(UpdateKind.RESTART, UpdateReport.Stage.COUNTDOWN));
        assertEquals("🔄 Stopping the servers for the take-down", title(UpdateKind.DOWN, UpdateReport.Stage.STOPPING));
        assertEquals("✅ Nothing to start", title(UpdateKind.START, UpdateReport.Stage.NOTHING_TO_DO));
        assertEquals("⏹️ Plugin removal cancelled", title(UpdateKind.REMOVE_PLUGIN, UpdateReport.Stage.CANCELLED));
        for (final UpdateKind kind : UpdateKind.values()) {
            for (final UpdateReport.Stage stage : UpdateReport.Stage.values()) {
                final String shown = title(kind, stage);
                assertFalse(shown.contains("{") || shown.contains("run."), "unrendered: " + shown);
            }
        }
    }

    @Test
    void theKindIsInTheTitleAndNotAgainAsAField() {
        rows.put(new UpdateRequest(
                1L,
                UpdateKind.BACKUP,
                UpdateStatus.DONE,
                OWNER,
                NOW,
                NOW,
                null,
                List.of(),
                NOW,
                NOW,
                reportAt(UpdateReport.Stage.DONE)));
        feed.tick();

        assertEquals(List.of("By", "Duration"), names(board.posted.getFirst().card()));
    }

    @Test
    void aBackupsLineSaysOnceThatItSavedAndNotThatItIsABackup() {
        final UpdateReport saved = UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine(
                        "database",
                        UpdateReport.State.SAVED,
                        List.of(UpdateReport.Change.told(
                                "backup",
                                TEXTS.report().saved(ByteSize.of(9_227_468).message(), Duration.ofSeconds(2)))),
                        null));
        final UpdateRequest backup = new UpdateRequest(
                1L, UpdateKind.BACKUP, UpdateStatus.DONE, OWNER, NOW, NOW, null, List.of(), NOW, NOW, null);

        final LayoutRenderer.Drawn card = UpdateFeed.card(saved, backup, layouts, false, null);

        assertEquals("✅ **database** *saved* 8.8 MiB in 2s", field(card, "Services"));
    }

    @Test
    void anArchivePutBackIsDoneAndSaysItWasRestored() {
        final UpdateReport restored = UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine(
                        "smp-1.tar.zst",
                        UpdateReport.State.RESTORED,
                        List.of(UpdateReport.Change.told(
                                "restore",
                                TEXTS.report().restored(ByteSize.of(1_258_291).message()))),
                        null));
        final UpdateRequest restore = new UpdateRequest(
                1L, UpdateKind.RESTORE, UpdateStatus.DONE, OWNER, NOW, NOW, null, List.of(), NOW, NOW, null);

        final LayoutRenderer.Drawn card = UpdateFeed.card(restored, restore, layouts, false, null);

        assertEquals("✅ **smp-1.tar.zst** *restored* 1.2 MiB", field(card, "Services"));
    }

    @Test
    void theCardLinksToTheRunsPageInStewardWhileItMovesAndOnceItIsDone() {
        rows.put(row(7L, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
        feed.tick();
        rows.put(row(7L, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
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
        final UpdateFeed unlinked = new UpdateFeed(rows, board, layouts, "");
        rows.put(row(7L, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
        unlinked.tick();

        assertEquals(null, link(board.posted.getFirst().card()));
    }

    @Test
    void aStartUpInstallThatWentWellIsNotPosted() {
        feed.start();
        rows.put(install(1L, UpdateStatus.DONE, UpdateReport.Stage.DONE));
        feed.tick();

        assertEquals(List.of(), board.posted, "the admins hear of a start-up install only when it failed");
    }

    @Test
    void aStartUpInstallThatFailedIsPostedUnderItsOwnTitle() {
        feed.start();
        rows.put(install(1L, UpdateStatus.FAILED, UpdateReport.Stage.FAILED));
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(
                "🛑 The start-up install failed", title(board.posted.getFirst().card()));
    }

    @Test
    void aStartUpInstallThatWentWellWhileTheBotWasDownIsNotCaughtUp() {
        rows.put(install(1L, UpdateStatus.DONE, UpdateReport.Stage.DONE));
        rows.put(install(2L, UpdateStatus.FAILED, UpdateReport.Stage.FAILED));
        feed.start();

        assertEquals(1, board.posted.size(), "only the failed one is caught up");
    }

    private static UpdateRequest install(final long id, final UpdateStatus status, final UpdateReport.Stage stage) {
        return new UpdateRequest(
                id, UpdateKind.INSTALL, status, Actor.STEWARD, NOW, NOW, null, List.of(), NOW, NOW, reportAt(stage));
    }

    private String title(final UpdateKind kind, final UpdateReport.Stage stage) {
        return title(UpdateFeed.card(
                UpdateReport.at(stage),
                new UpdateRequest(1L, kind, UpdateStatus.RUNNING, OWNER, NOW, NOW, null, List.of(), NOW, null, null),
                layouts,
                false,
                null));
    }

    private static String title(final LayoutRenderer.Drawn card) {
        return card.text().lines().findFirst().orElseThrow().replaceFirst("^## ", "");
    }

    /** Returns the sections of the card in order: the by and duration lines, then each heading. */
    private static List<String> names(final LayoutRenderer.Drawn card) {
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

    private static String field(final LayoutRenderer.Drawn card, final String name) {
        final List<String> all = card.text().lines().toList();
        final int heading = all.indexOf("### " + name);
        if (heading < 0) {
            throw new AssertionError("no heading " + name + " in " + names(card));
        }
        return all.subList(heading + 1, all.size()).stream()
                .takeWhile(line -> !line.startsWith("### "))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String lines(final LayoutRenderer.Drawn card) {
        return card.text();
    }

    private static @Nullable Integer accent(final LayoutRenderer.Drawn card) {
        return MessageComponentTree.of(card.components())
                .findAll(net.dv8tion.jda.api.components.container.Container.class)
                .getFirst()
                .getAccentColorRaw();
    }

    private static @Nullable Button link(final LayoutRenderer.Drawn card) {
        return MessageComponentTree.of(card.components()).findAll(Button.class).stream()
                .filter(button -> button.getStyle() == ButtonStyle.LINK)
                .findFirst()
                .orElse(null);
    }
}
