package eu.nordtal.season.steward.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.steward.texts.StewardTexts;
import java.time.Instant;
import java.util.LinkedHashMap;
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

    private static Request<BotRequest> row(
            final long id,
            final InboxStatus status,
            final Map<String, String> words,
            final BotRequest.Announce.Posted... lines) {
        final Instant at = Instant.EPOCH;
        return new Request<>(
                id,
                "ANNOUNCE",
                BotRequest.Announce.words(new LinkedHashMap<>(words)),
                status,
                Actor.STEWARD,
                at,
                at,
                null,
                at,
                at,
                null,
                lines.length == 0 ? null : new BotRequest.Announce.Answer(List.of(lines)),
                null);
    }

    private static BotRequest.Announce.Posted posted(final String language) {
        return new BotRequest.Announce.Posted(language, true);
    }

    private static BotRequest.Announce.Posted missed(final String language) {
        return new BotRequest.Announce.Posted(language, false);
    }

    @Test
    void anAnnouncementThatFailedForOneLanguageIsOpenWithTheTextThatWentOut() {
        assertEquals(
                List.of(),
                Announcements.open(List.of(row(1, InboxStatus.DONE, Map.of("en", "Hello"), posted("en")))),
                "an announcement that went out in full is not open");

        final Map<String, String> words = new LinkedHashMap<>();
        words.put("en", "Hello");
        words.put("de", "Hallo");
        assertEquals(
                List.of(
                        new Announcements.OpenLine("en", "Hello", true),
                        new Announcements.OpenLine("de", "Hallo", false)),
                Announcements.open(List.of(row(2, InboxStatus.DONE, words, posted("en"), missed("de")))));
    }

    @Test
    void aRetryThatFailedAgainKeepsTheLanguagesItsFirstSendPosted() {
        final Map<String, String> words = new LinkedHashMap<>();
        words.put("en", "Hello");
        words.put("de", "Hallo");
        final Request<BotRequest> first = row(3, InboxStatus.DONE, words, posted("en"), missed("de"));
        final Request<BotRequest> retry = row(4, InboxStatus.FAILED, Map.of("de", "Hallo!"));
        final Request<BotRequest> earlier = row(1, InboxStatus.DONE, Map.of("en", "Before"), missed("en"));

        assertEquals(
                List.of(
                        new Announcements.OpenLine("de", "Hallo!", false),
                        new Announcements.OpenLine("en", "Hello", true)),
                Announcements.open(List.of(retry, first, earlier)));
    }

    @Test
    void nothingIsOpenWhileTheNewestRunsOrOnceARetryWentOut() {
        final Map<String, String> words = new LinkedHashMap<>();
        words.put("en", "Hello");
        words.put("de", "Hallo");
        final Request<BotRequest> first = row(3, InboxStatus.DONE, words, posted("en"), missed("de"));

        assertEquals(List.of(), Announcements.open(List.of(row(4, InboxStatus.PENDING, Map.of("de", "Hallo")), first)));
        assertEquals(
                List.of(),
                Announcements.open(List.of(row(4, InboxStatus.DONE, Map.of("de", "Hallo"), posted("de")), first)));
        assertEquals(List.of(), Announcements.open(List.of()));
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
