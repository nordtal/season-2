package eu.nordtal.season.database.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.messages.document.Document;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Drafts, sends and retries an admin's announcement against PostgreSQL and the real migrations. */
class AnnouncementBookIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("123456789012345678"));
    private static final Schedule WITHIN = Schedule.within(Duration.ofMinutes(1));
    private static final Document HELLO = Document.decode("""
            {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Hello", "de": "Hallo"}}]}]}""");
    private static final Document BYE = Document.decode("""
            {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Bye", "de": "Tschüss"}}]}]}""");

    private static Map<String, String> channels(final String... pairs) {
        final Map<String, String> channels = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            channels.put(pairs[i], pairs[i + 1]);
        }
        return channels;
    }

    private static List<AnnouncementBook.Delivery> deliveries(final AnnouncementBook.Announcement announcement) {
        return announcement.targets().stream()
                .map(AnnouncementBook.Target::delivery)
                .toList();
    }

    @Test
    void aDraftChangesUntilItIsSentAndASendPostsEachOfItsChannels() {
        final TestDatabase database = TestDatabase.fresh();
        final AnnouncementBook steward = AnnouncementBook.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final long id = steward.create(HELLO, channels("de", "111", "en", "222"));
        assertTrue(steward.change(id, BYE, channels("de", "333", "en", "222")), "a draft changes");
        final AnnouncementBook.Announcement draft = steward.find(id).orElseThrow();
        assertEquals(BYE, draft.document());
        assertEquals(
                List.of(
                        new AnnouncementBook.Target("de", "333", AnnouncementBook.Delivery.UNSENT),
                        new AnnouncementBook.Target("en", "222", AnnouncementBook.Delivery.UNSENT)),
                draft.targets());

        final AnnouncementBook.Sent sent = steward.send(id, ADMIN, WITHIN).orElseThrow();
        assertEquals(List.of("de", "en"), sent.languages());
        assertFalse(steward.change(id, HELLO, channels("en", "222")), "a sent announcement no longer changes");
        assertFalse(steward.delete(id), "a sent announcement stays");
        assertEquals(
                List.of(AnnouncementBook.Delivery.SENDING, AnnouncementBook.Delivery.SENDING),
                deliveries(steward.find(id).orElseThrow()));

        final Inbox<BotRequest> bot = Inbox.over(database.dataSourceAs(DatabaseRole.DISCORD_BOT), BotRequest.TABLE);
        final Request<BotRequest> claimed = bot.claim().orElseThrow();
        assertEquals(sent.request().orElseThrow(), claimed.id());
        assertEquals(
                new BotRequest.PostAnnouncement(
                        id,
                        List.of(
                                new BotRequest.PostAnnouncement.Target("de", "333"),
                                new BotRequest.PostAnnouncement.Target("en", "222"))),
                claimed.payload());
    }

    @Test
    void aRetryPostsOnlyTheLanguageTheBotCouldNotPost() {
        final TestDatabase database = TestDatabase.fresh();
        final AnnouncementBook steward = AnnouncementBook.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final AnnouncementBook asBot = AnnouncementBook.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT));
        final Inbox<BotRequest> bot = Inbox.over(database.dataSourceAs(DatabaseRole.DISCORD_BOT), BotRequest.TABLE);
        final long id = steward.create(HELLO, channels("de", "111", "en", "222"));
        final long request =
                steward.send(id, ADMIN, WITHIN).orElseThrow().request().orElseThrow();
        assertEquals(request, bot.claim().orElseThrow().id());
        bot.settle(
                request,
                Outcome.done(new BotRequest.PostAnnouncement.Answer(List.of(
                        new BotRequest.Announce.Posted("de", false), new BotRequest.Announce.Posted("en", true)))));
        asBot.posted(id, List.of("en"));
        assertEquals(
                List.of(AnnouncementBook.Delivery.NOT_POSTED, AnnouncementBook.Delivery.POSTED),
                deliveries(steward.all().getFirst()));

        final AnnouncementBook.Sent retried = steward.send(id, ADMIN, WITHIN).orElseThrow();
        assertEquals(List.of("de"), retried.languages());
        assertEquals(
                List.of(new BotRequest.PostAnnouncement.Target("de", "111")),
                ((BotRequest.PostAnnouncement) bot.claim().orElseThrow().payload()).targets());
        assertTrue(
                steward.send(id, ADMIN, WITHIN).orElseThrow().request().isEmpty(),
                "nothing is asked twice while the retry is on its way");
    }

    @Test
    void aDraftWithOneChannelGoesOutInThatLanguageAloneAndOneWithoutAnyIsNotSent() {
        final TestDatabase database = TestDatabase.fresh();
        final AnnouncementBook steward = AnnouncementBook.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final long english = steward.create(HELLO, channels("en", "222"));
        assertEquals(
                List.of("en"),
                steward.send(english, ADMIN, WITHIN).orElseThrow().languages());

        final long none = steward.create(HELLO, Map.of());
        assertTrue(steward.send(none, ADMIN, WITHIN).isEmpty(), "a draft without a channel is not sent");
        assertTrue(steward.delete(none), "a draft is deleted");
        assertTrue(steward.find(none).isEmpty());
    }
}
