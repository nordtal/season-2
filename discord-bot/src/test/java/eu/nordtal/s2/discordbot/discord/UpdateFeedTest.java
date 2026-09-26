package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
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

/**
 * What the admin channel is told about a run nobody in Discord started.
 *
 * Why these are worth a test at all: Every one of them was previously answerable only by watching a real guild
 * during a real update, which happens about once a season - and three of the four are silent when they are wrong. A
 * feed that starts from zero fills the channel with a season of history; one that starts from {@code max(id)} and
 * nothing else silently loses the run that finished during the restart; one that does not skip {@code DISCORD} rows
 * puts two drawings of the same run in front of the same people and they disagree the moment the interaction token
 * expires.
 */
class UpdateFeedTest {

    private static final Instant NOW = Instant.parse("2026-09-08T20:00:00Z");

    /** What was posted and edited, instead of a guild. */
    private static final class Board implements UpdateFeed.Board {

        record Post(MessageEmbed embed, String messageId) {}

        final List<Post> posted = new ArrayList<>();
        final List<Post> edited = new ArrayList<>();
        final List<String> alerted = new ArrayList<>();

        /** Whether Discord acknowledges a post, so "no message id yet" can be driven too. */
        boolean acknowledge = true;

        @Override
        public void post(final MessageEmbed embed, final Consumer<String> sentId) {
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

        @Override
        public void alert(final String text) {
            alerted.add(text);
        }
    }

    /** Only the four reads the feed makes; everything else is somebody else's business. */
    private static final class Rows implements UpdateDirectory {

        @Override
        public java.util.List<UpdateRequest> recent(final int limit) {
            // The feed follows rows by id, one at a time; a page of recent ones is the web interface's question.
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
                final UpdateKind kind, final UpdateSource source, final String requestedBy, final Duration delay) {
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
        public Optional<UpdateRequest> startCountdown(final long id, final Duration seconds) {
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
        public Optional<UpdateRequest> cancelCountdown(final String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> nextDue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int settleOrphans(final String failed) {
            throw new UnsupportedOperationException();
        }
    }

    private final Rows rows = new Rows();
    private final Board board = new Board();
    private final Messages messages =
            Messages.load(UpdateFeedTest.class.getClassLoader(), "messages/commands", Locale.ENGLISH, Locale.GERMAN);
    private final UpdateFeed feed = new UpdateFeed(rows, board, messages);

    @Test
    void aPassIsAdmittedBeforeTheHandOverSoABusyPoolQueuesOneAndNotThirty() {
        // The executor stands in for four workers busy with payments, taking the task and holding it before it runs.
        final List<Runnable> queued = new ArrayList<>();
        final java.util.concurrent.Executor busy = queued::add;

        rows.put(row(1, UpdateSource.GAME, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));

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
    void aPoolThatRefusesThePassReleasesTheFlagInsteadOfSwitchingTheFeedOff() {
        final java.util.concurrent.Executor shuttingDown = task -> {
            throw new java.util.concurrent.RejectedExecutionException("shutting down");
        };
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> feed.submit(shuttingDown));

        // Without the release in the catch, one rejection during a restart would leave the feed silent for good.
        rows.put(row(1, UpdateSource.GAME, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
        final List<Runnable> queued = new ArrayList<>();
        feed.submit(queued::add);
        assertEquals(1, queued.size(), "the flag stayed taken after a rejected submission");
    }

    @Test
    void aRunThatFailsMentionsTheAdminRoleOneThatSucceedsDoesNot() {
        rows.put(row(1, UpdateSource.GAME, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
        feed.tick();
        assertTrue(board.alerted.isEmpty(), "a run still going is not news for the admin role");

        rows.put(row(1, UpdateSource.GAME, UpdateStatus.FAILED, reportAt(UpdateReport.Stage.STOPPING), NOW));
        feed.tick();
        assertEquals(
                1,
                board.alerted.size(),
                "a failed run did not mention the admin role. Editing the embed notifies nobody -"
                        + " which is the whole point at five in the morning, when the backup gave up"
                        + " waiting and the only other trace is a message turning red on a screen"
                        + " nobody is looking at");

        feed.tick();
        assertEquals(
                1,
                board.alerted.size(),
                "the same failed run was announced twice. It is removed from the follow list at the"
                        + " moment it finishes, so a second mention means the two paths that can"
                        + " announce one both fired");
    }

    @Test
    void aCancelledRunSaysNothingSomebodyTypedThatAndAlreadyKnows() {
        rows.put(row(2, UpdateSource.GAME, UpdateStatus.CANCELLED, reportAt(UpdateReport.Stage.RESOLVING), NOW));
        feed.tick();

        assertEquals(1, board.posted.size(), "a cancelled run is still worth a line in the channel");
        assertTrue(
                board.alerted.isEmpty(),
                "a cancellation mentioned the admin role. A ping that fires for something somebody"
                        + " chose is a ping people learn to ignore, which is the same as none");
    }

    @Test
    void aRunThatWasAlreadyFailedWhenFirstSeenIsAnnouncedToo() {
        rows.put(row(3, UpdateSource.CONSOLE, UpdateStatus.FAILED, reportAt(UpdateReport.Stage.STOPPING), NOW));
        feed.tick();

        assertEquals(
                1,
                board.alerted.size(),
                "a row that arrives already failed was posted but not announced. That is exactly the"
                        + " run nobody watched - it went wrong while the bot was restarting");
    }

    private static UpdateRequest row(
            final long id,
            final UpdateSource source,
            final UpdateStatus status,
            final String result,
            final Instant finished) {
        return new UpdateRequest(id, UpdateKind.UPDATE, status, source, "Till", NOW, NOW, NOW, finished, result);
    }

    private static String reportAt(final UpdateReport.Stage stage) {
        return UpdateReports.toJson(UpdateReport.at(stage));
    }

    @Test
    void aRunStartedInGameIsPostedAndEditedAsItMoves() {
        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.RESOLVING), null));
        feed.tick();

        assertEquals(1, board.posted.size());
        final java.util.Map<String, String> context = new java.util.HashMap<>();
        board.posted.getFirst().embed().getFields().forEach(f -> context.put(f.getName(), f.getValue()));
        assertEquals(
                "Till", context.get("By"), "who asked is the half the asker's own embed omits, and here it is a field");
        assertEquals("game", context.get("From"));

        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
        feed.tick();
        assertEquals(1, board.posted.size(), "the same run, not a second message");
        assertEquals(1, board.edited.size());
        assertEquals("msg-1", board.edited.getFirst().messageId());

        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
        feed.tick();
        assertEquals(2, board.edited.size(), "and the last edit is the answer");

        feed.tick();
        assertEquals(2, board.edited.size(), "a settled run is not followed any more");
    }

    @Test
    void anUnchangedReportIsNotReSentBecauseDiscordRateLimitsEdits() {
        rows.put(row(1L, UpdateSource.CONSOLE, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.INSTALLING), null));
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
    void aRunStartedInDiscordIsLeftAloneItAlreadyHasAnEmbed() {
        rows.put(row(1L, UpdateSource.DISCORD, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING), null));
        feed.tick();

        assertEquals(
                List.of(),
                board.posted,
                "two drawings of one run in the same guild disagree the moment the interaction"
                        + " token expires, and the asker's own is the one that stops first");

        rows.put(row(1L, UpdateSource.DISCORD, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
        feed.tick();
        assertEquals(List.of(), board.edited);
    }

    @Test
    void aRestartBeginsAtTheNewestRowSoASeasonOfHistoryIsNotRePosted() {
        for (long id = 1; id <= 40; id++) {
            rows.put(row(
                    id,
                    UpdateSource.GAME,
                    UpdateStatus.DONE,
                    reportAt(UpdateReport.Stage.DONE),
                    NOW.minus(Duration.ofDays(3))));
        }
        feed.start();
        feed.tick();

        assertEquals(List.of(), board.posted);
    }

    @Test
    void aRunThatEndedWhileTheBotWasDownIsPostedOnceAsAResult() {
        // Easy to leave out and silent when missing: a row below max(id) is one the feed would never look at again.
        rows.put(row(
                1L,
                UpdateSource.GAME,
                UpdateStatus.FAILED,
                reportAt(UpdateReport.Stage.FAILED),
                NOW.minus(Duration.ofMinutes(4))));
        rows.put(row(
                2L,
                UpdateSource.GAME,
                UpdateStatus.DONE,
                reportAt(UpdateReport.Stage.DONE),
                NOW.minus(Duration.ofHours(9))));
        feed.start();

        assertEquals(1, board.posted.size(), "and nothing older than the catch-up window");
        feed.tick();
        assertEquals(1, board.posted.size(), "posted once; there is nothing left to edit into it");
    }

    @Test
    void aPostDiscordNeverAcknowledgedIsNotEditedAgainstAMessageIdThatIsNotThere() {
        board.acknowledge = false;
        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.RESOLVING), null));
        feed.tick();

        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
        feed.tick();

        assertEquals(
                List.of(),
                board.edited,
                "the message id arrives on a JDA thread and may never arrive at all; the row is the"
                        + " record and the embed is only a drawing of it");
    }

    @Test
    void aRunAlreadyFinishedWhenTheFeedFirstSeesItIsPostedOnceAndNotFollowed() {
        rows.put(row(1L, UpdateSource.CONSOLE, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE), NOW));
        feed.tick();
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(List.of(), board.edited);
    }
}
