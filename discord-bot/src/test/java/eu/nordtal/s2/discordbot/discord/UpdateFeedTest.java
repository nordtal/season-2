package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.update.ByteSize;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.messages.Messages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

/** What the admin channel is told about a run nobody in Discord started. */
class UpdateFeedTest {

    private static final Instant NOW = Instant.parse("2026-09-08T20:00:00Z");

    /** What was posted and edited, instead of a guild. */
    private static final class Board implements UpdateFeed.Board {

        record Post(MessageEmbed embed, String messageId) {}

        final List<Post> posted = new ArrayList<>();
        final List<Post> edited = new ArrayList<>();

        /** Whether Discord acknowledges a post, so "no message id yet" can be driven too. */
        boolean acknowledge = true;

        /** Runs inside the next post, standing in for a signal that arrives in the middle of a pass. */
        Runnable duringPost = () -> {};

        @Override
        public void post(final MessageEmbed embed, final Consumer<String> sentId) {
            final Runnable during = duringPost;
            duringPost = () -> {};
            during.run();
            final String id = "msg-" + (posted.size() + 1);
            posted.add(new Post(embed, id));
            if (acknowledge) {
                sentId.accept(id);
            }
        }

        @Override
        public void edit(final String messageId, final MessageEmbed embed) {
            edited.add(new Post(embed, messageId));
        }
    }

    /** Only the four reads the feed makes. */
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
                final eu.nordtal.s2.database.inbox.StewardRequest request, final Actor actor, final Duration delay) {
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
                final eu.nordtal.s2.messages.MessageRef why, final java.util.function.Predicate<String> stillRunning) {
            throw new UnsupportedOperationException();
        }
    }

    private final Rows rows = new Rows();
    private final Board board = new Board();
    private final DiscordRenderer messages = DiscordRenderer.of(Messages.load(
            UpdateFeedTest.class.getClassLoader(), List.of("messages/access", "messages/admin"), Locale.ENGLISH));
    private final UpdateFeed feed = new UpdateFeed(rows, board, messages, Clock.systemUTC());

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
        // The run moves on while the first pass is drawing it, and the signal of that write arrives mid-pass.
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

        // Without the release in the catch, one rejection would silence the feed for good.
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

    private static final Actor OWNER = Actor.person(eu.nordtal.s2.common.id.DiscordId.of("300000000000000077"));

    private static UpdateRequest row(
            final long id, final UpdateStatus status, final String result, final Instant finished) {
        return new UpdateRequest(id, UpdateKind.UPDATE, status, OWNER, NOW, NOW, null, List.of(), NOW, finished, result);
    }

    private static String reportAt(final UpdateReport.Stage stage) {
        return UpdateReports.toJson(UpdateReport.at(stage));
    }

    @Test
    void aRunStartedInGameIsPostedAndEditedAsItMoves() {
        rows.put(row(1L, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.RESOLVING), null));
        feed.tick();

        assertEquals(1, board.posted.size());
        final java.util.Map<String, String> context = new java.util.HashMap<>();
        board.posted.getFirst().embed().getFields().forEach(f -> context.put(f.getName(), f.getValue()));
        assertEquals("<@300000000000000077>", context.get("By"), "who asked is a field of its own, as a mention");

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
        // A row below max(id) is one the feed would never look at again.
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
                        + " record and the embed is only a drawing of it");
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
    void theAskerIsAMentionForAPersonAndAWordForStewardAndTheHost() {
        final java.util.function.Function<Actor, String> asker = actor -> UpdateFeed.asker(
                new UpdateRequest(
                        1L, UpdateKind.UPDATE, UpdateStatus.FAILED, actor, NOW, NOW, null, List.of(), NOW, NOW, null),
                messages);

        assertEquals("<@300000000000000077>", asker.apply(OWNER), "Discord renders a mention for a person");
        assertEquals("Steward", asker.apply(Actor.STEWARD));
        assertEquals("the host", asker.apply(Actor.HOST));
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

        final MessageEmbed good = board.posted.get(0).embed();
        final MessageEmbed bad = board.posted.get(1).embed();
        assertEquals(good.getColorRaw(), bad.getColorRaw(), "a finished and a failed run carry the same line");
        assertTrue(good.getTitle().startsWith("✅"), good.getTitle());
        assertTrue(bad.getTitle().startsWith("🛑"), bad.getTitle());
        final String goodLines = good.getFields().stream()
                .map(MessageEmbed.Field::getValue)
                .collect(java.util.stream.Collectors.joining("\n"));
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

        final String now = lines(board.posted.get(0).embed());
        final String before = lines(board.posted.get(1).embed());
        assertTrue(now.contains("image *out of date*"), now);
        assertFalse(now.contains("report.image-outdated"), now);
        assertFalse(now.contains("**-**"), "the placeholder of a version is never shown: " + now);
        assertTrue(before.contains("image **out of date**"), before);
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

        final String shown = lines(board.posted.getFirst().embed());
        assertTrue(shown.contains("backup *saved 1.2 MiB in 12s*"), shown);
        assertFalse(shown.contains("report."), "no key is ever shown in place of its words: " + shown);
    }

    private static String lines(final MessageEmbed embed) {
        return embed.getFields().stream()
                .map(MessageEmbed.Field::getValue)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
