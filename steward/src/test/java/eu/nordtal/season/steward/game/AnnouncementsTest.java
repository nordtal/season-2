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

    private static Request<BotRequest> done(final BotRequest payload, final Object answer) {
        final Instant at = Instant.EPOCH;
        return new Request<>(
                1,
                payload instanceof BotRequest.PostAnnouncement ? "POST_ANNOUNCEMENT" : "ANNOUNCE",
                payload,
                InboxStatus.DONE,
                Actor.STEWARD,
                at,
                at,
                null,
                at,
                at,
                null,
                answer,
                null);
    }

    private static Request<BotRequest> words(final BotRequest.Announce.Posted... lines) {
        return done(
                BotRequest.Announce.words(Map.of("en", "Hello", "de", "Hallo")),
                new BotRequest.Announce.Answer(List.of(lines)));
    }

    private static Request<BotRequest> draft(final BotRequest.Announce.Posted... targets) {
        return done(
                new BotRequest.PostAnnouncement(
                        7,
                        List.of(
                                new BotRequest.PostAnnouncement.Target("de", "111"),
                                new BotRequest.PostAnnouncement.Target("en", "222"))),
                new BotRequest.PostAnnouncement.Answer(List.of(targets)));
    }

    @Test
    void aLineTheBotDidNotPostFailedWithItsReasonNamingTheLanguage() {
        for (final Request<BotRequest> row : List.of(
                words(new BotRequest.Announce.Posted("en", true), new BotRequest.Announce.Posted("de", false)),
                draft(new BotRequest.Announce.Posted("en", true), new BotRequest.Announce.Posted("de", false)))) {
            assertEquals(InboxStatus.FAILED, Announcements.status(row, "de"));
            assertEquals(
                    StewardTexts.TEXTS.steward().said().notAnnounced("Deutsch"),
                    Announcements.result(row, "de").orElseThrow());
        }
    }

    @Test
    void aPostedLineIsDoneWithoutWords() {
        for (final Request<BotRequest> row : List.of(
                words(new BotRequest.Announce.Posted("en", true)), draft(new BotRequest.Announce.Posted("en", true)))) {
            assertEquals(InboxStatus.DONE, Announcements.status(row, "en"));
            assertTrue(Announcements.result(row, "en").isEmpty());
        }
    }
}
