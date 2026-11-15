package eu.nordtal.season.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.discord.UpdateFeedFakes.Board;
import eu.nordtal.season.discordbot.discord.UpdateFeedFakes.Rows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A bot that was down for longer than the catch-up window, and the cards it kept from before. */
class UpdateFeedLongOutageTest {

    private static final Instant NOW = UpdateFeedFakes.NOW;

    private static final Actor OWNER = Actor.person(DiscordId.of("300000000000000077"));

    private final Rows rows = new Rows();
    private final Board board = new Board();
    private final RunCards cards = RunCards.inMemory();
    private final List<Runnable> queued = new ArrayList<>(); // passes nobody runs
    private final UpdateFeed feed = restarted();

    /** A feed as the bot builds it at start, over the same rows, channel and kept cards. */
    private UpdateFeed restarted() {
        return new UpdateFeed(
                rows, board, cards, StoredDocumentsStandIn.renderer(), "https://steward.example/", queued::add);
    }

    private static UpdateRequest row(
            final long id,
            final UpdateStatus status,
            final @Nullable RunStage stage,
            final @Nullable Instant finished) {
        return new UpdateRequest(id, UpdateKind.UPDATE, status, OWNER, NOW, NOW, null, List.of(), NOW, finished, stage);
    }

    @Test
    void aRunThatSettledWhileTheBotWasDownForLongGetsItsKeptCardRedrawnOnceOnStart() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();

        // The bot stays down far beyond the catch-up window, and the run ends meanwhile.
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW.minus(Duration.ofHours(9))));
        restarted().start();

        assertEquals(1, board.posted.size(), "the redraw posted a second card beside the first");
        assertEquals(
                List.of("msg-1"),
                board.edited.stream().map(Board.Post::messageId).toList());
        assertTrue(
                board.edited.getLast().card().text().contains("Took "),
                board.edited.getLast().card().text());

        restarted().start();
        assertEquals(1, board.edited.size(), "a card drawn to the run's end was drawn again on the next start");
    }

    @Test
    void aCardDrawnToTheRunsEndIsNotDrawnAgainOnALaterStart() {
        rows.put(row(1L, UpdateStatus.RUNNING, RunStage.STOPPING, null));
        feed.tick();
        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW));
        feed.tick();
        final int edits = board.edited.size();

        rows.put(row(1L, UpdateStatus.DONE, RunStage.DONE, NOW.minus(Duration.ofHours(9))));
        restarted().start();

        assertEquals(edits, board.edited.size(), "the start redrew a card the bot had drawn to the end");
    }
}
