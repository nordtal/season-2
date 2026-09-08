package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the admin channel is told about a run nobody in Discord started.
 *
 * <h2>Why these are worth a test at all</h2>
 * Every one of them was previously answerable only by watching a real guild during a real update,
 * which happens about once a season - and three of the four are silent when they are wrong. A feed
 * that starts from zero fills the channel with a season of history; one that starts from
 * {@code max(id)} and nothing else silently loses the run that finished during the restart; one that
 * does not skip {@code DISCORD} rows puts two drawings of the same run in front of the same people
 * and they disagree the moment the interaction token expires.
 */
class UpdateFeedTest {

    private static final Instant NOW = Instant.parse("2026-09-08T20:00:00Z");

    /** What was posted and edited, instead of a guild. */
    private static final class Board implements UpdateFeed.Board {

        record Post(MessageEmbed embed, String messageId) {
        }

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

        final Map<Long, UpdateRequest> byId = new LinkedHashMap<>();

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
        public UpdateRequest submit(final UpdateKind kind, final UpdateSource source,
                                    final String requestedBy, final Duration delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> claimNext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UpdateRequest> finish(final long id, final UpdateStatus status,
                                              final String result) {
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
    private final Messages messages = Messages.load(UpdateFeedTest.class.getClassLoader(),
            "messages/commands", Locale.ENGLISH, Locale.GERMAN);
    private final UpdateFeed feed = new UpdateFeed(rows, board, messages);

    @Test
    @DisplayName("a run that fails mentions the admin role; one that succeeds does not")
    void onlyAFailureIsWorthAPing() {
        rows.put(row(1, UpdateSource.GAME, UpdateStatus.RUNNING, reportAt(UpdateReport.Stage.STOPPING),
                null));
        feed.tick();
        assertTrue(board.alerted.isEmpty(), "a run still going is not news for the admin role");

        rows.put(row(1, UpdateSource.GAME, UpdateStatus.FAILED, reportAt(UpdateReport.Stage.STOPPING),
                NOW));
        feed.tick();
        assertEquals(1, board.alerted.size(),
                "a failed run did not mention the admin role. Editing the embed notifies nobody -"
                        + " which is the whole point at five in the morning, when the backup gave up"
                        + " waiting and the only other trace is a message turning red on a screen"
                        + " nobody is looking at");

        feed.tick();
        assertEquals(1, board.alerted.size(),
                "the same failed run was announced twice. It is removed from the follow list at the"
                        + " moment it finishes, so a second mention means the two paths that can"
                        + " announce one both fired");
    }

    @Test
    @DisplayName("a cancelled run says nothing - somebody typed that and already knows")
    void aCancellationIsNotAFailure() {
        rows.put(row(2, UpdateSource.GAME, UpdateStatus.CANCELLED, reportAt(UpdateReport.Stage.RESOLVING),
                NOW));
        feed.tick();

        assertEquals(1, board.posted.size(), "a cancelled run is still worth a line in the channel");
        assertTrue(board.alerted.isEmpty(),
                "a cancellation mentioned the admin role. A ping that fires for something somebody"
                        + " chose is a ping people learn to ignore, which is the same as none");
    }

    @Test
    @DisplayName("a run that was already failed when first seen is announced too")
    void aRunThatFinishedWhileTheBotWasDownIsAnnounced() {
        rows.put(row(3, UpdateSource.CONSOLE, UpdateStatus.FAILED, reportAt(UpdateReport.Stage.STOPPING),
                NOW));
        feed.tick();

        assertEquals(1, board.alerted.size(),
                "a row that arrives already failed was posted but not announced. That is exactly the"
                        + " run nobody watched - it went wrong while the bot was restarting");
    }

    private static UpdateRequest row(final long id, final UpdateSource source,
                                     final UpdateStatus status, final String result,
                                     final Instant finished) {
        return new UpdateRequest(id, UpdateKind.UPDATE, status, source, "Till", NOW, NOW, NOW,
                finished, result);
    }

    private static String reportAt(final UpdateReport.Stage stage) {
        return UpdateReports.toJson(UpdateReport.at(stage));
    }

    @Test
    @DisplayName("a run started in game is posted, and edited as it moves")
    void aGameRunIsDrawnAndKeptUpToDate() {
        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.RUNNING,
                reportAt(UpdateReport.Stage.RESOLVING), null));
        feed.tick();

        assertEquals(1, board.posted.size());
        assertTrue(board.posted.getFirst().embed().getFooter().getText().contains("Till"),
                "the footer is the half the asker's own embed omits: who asked, and from where");

        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.RUNNING,
                reportAt(UpdateReport.Stage.STOPPING), null));
        feed.tick();
        assertEquals(1, board.posted.size(), "the same run, not a second message");
        assertEquals(1, board.edited.size());
        assertEquals("msg-1", board.edited.getFirst().messageId());

        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.DONE,
                reportAt(UpdateReport.Stage.DONE), NOW));
        feed.tick();
        assertEquals(2, board.edited.size(), "and the last edit is the answer");

        feed.tick();
        assertEquals(2, board.edited.size(), "a settled run is not followed any more");
    }

    @Test
    @DisplayName("an unchanged report is not re-sent, because Discord rate-limits edits")
    void anIdenticalReportIsNotRedrawn() {
        rows.put(row(1L, UpdateSource.CONSOLE, UpdateStatus.RUNNING,
                reportAt(UpdateReport.Stage.INSTALLING), null));
        feed.tick();
        feed.tick();
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(List.of(), board.edited,
                "this polls every two seconds and a run writes a stage at a time; twenty identical"
                        + " edits between two stages would spend the edit budget on nothing");
    }

    @Test
    @DisplayName("a run started in Discord is left alone - it already has an embed")
    void discordRunsAreSkipped() {
        rows.put(row(1L, UpdateSource.DISCORD, UpdateStatus.RUNNING,
                reportAt(UpdateReport.Stage.STOPPING), null));
        feed.tick();

        assertEquals(List.of(), board.posted,
                "two drawings of one run in the same guild disagree the moment the interaction"
                        + " token expires, and the asker's own is the one that stops first");

        rows.put(row(1L, UpdateSource.DISCORD, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE),
                NOW));
        feed.tick();
        assertEquals(List.of(), board.edited);
    }

    @Test
    @DisplayName("a restart begins at the newest row, so a season of history is not re-posted")
    void aRestartDoesNotReplayTheTable() {
        for (long id = 1; id <= 40; id++) {
            rows.put(row(id, UpdateSource.GAME, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE),
                    NOW.minus(Duration.ofDays(3))));
        }
        feed.start();
        feed.tick();

        assertEquals(List.of(), board.posted);
    }

    @Test
    @DisplayName("a run that ended while the bot was down is posted once, as a result")
    void theRunNobodySawIsStillAnswered() {
        // The half that is easy to leave out, and it is silent when it is missing: the row's id is
        // below max(id), so the feed would never look at it again, and the one run nobody was
        // watching would also be the one run nobody ever saw the answer to.
        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.FAILED, reportAt(UpdateReport.Stage.FAILED),
                NOW.minus(Duration.ofMinutes(4))));
        rows.put(row(2L, UpdateSource.GAME, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE),
                NOW.minus(Duration.ofHours(9))));
        feed.start();

        assertEquals(1, board.posted.size(), "and nothing older than the catch-up window");
        feed.tick();
        assertEquals(1, board.posted.size(), "posted once; there is nothing left to edit into it");
    }

    @Test
    @DisplayName("a post Discord never acknowledged is not edited against a message id that is not there")
    void anUnacknowledgedPostIsNotFollowed() {
        board.acknowledge = false;
        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.RUNNING,
                reportAt(UpdateReport.Stage.RESOLVING), null));
        feed.tick();

        rows.put(row(1L, UpdateSource.GAME, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE),
                NOW));
        feed.tick();

        assertEquals(List.of(), board.edited,
                "the message id arrives on a JDA thread and may never arrive at all; the row is the"
                        + " record and the embed is only a drawing of it");
    }

    @Test
    @DisplayName("a run already finished when the feed first sees it is posted once and not followed")
    void aRunThatIsAlreadyOverIsNotFollowed() {
        rows.put(row(1L, UpdateSource.CONSOLE, UpdateStatus.DONE, reportAt(UpdateReport.Stage.DONE),
                NOW));
        feed.tick();
        feed.tick();

        assertEquals(1, board.posted.size());
        assertEquals(List.of(), board.edited);
    }
}
