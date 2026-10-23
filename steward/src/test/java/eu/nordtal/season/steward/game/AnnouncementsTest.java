package eu.nordtal.season.steward.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.steward.texts.StewardTexts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnnouncementsTest {

    private static Request<BotRequest> done(final BotRequest.Announce.Posted... lines) {
        final Instant at = Instant.EPOCH;
        return new Request<>(
                1,
                "ANNOUNCE",
                BotRequest.Announce.words(Map.of("en", "Hello", "de", "Hallo")),
                InboxStatus.DONE,
                Actor.STEWARD,
                at,
                at,
                null,
                at,
                at,
                null,
                new BotRequest.Announce.Answer(List.of(lines)),
                null);
    }

    @Test
    void aLineTheBotDidNotPostFailedWithItsReasonNamingTheLanguage() {
        final Request<BotRequest> row =
                done(new BotRequest.Announce.Posted("en", true), new BotRequest.Announce.Posted("de", false));

        assertEquals(InboxStatus.FAILED, Announcements.status(row, "de"));
        assertEquals(
                StewardTexts.TEXTS.steward().said().notAnnounced("Deutsch"),
                Announcements.result(row, "de").orElseThrow());
    }

    @Test
    void aPostedLineIsDoneWithoutWords() {
        final Request<BotRequest> row = done(new BotRequest.Announce.Posted("en", true));

        assertEquals(InboxStatus.DONE, Announcements.status(row, "en"));
        assertTrue(Announcements.result(row, "en").isEmpty());
    }
}
