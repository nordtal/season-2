package eu.nordtal.season.smp.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.messages.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

class AnnouncerTest {

    private static final List<Locale> LOADED = List.of(Locale.ENGLISH, Locale.GERMAN);
    private static final Messages BUNDLE =
            Messages.load(AnnouncerTest.class.getClassLoader(), "messages/database", Locale.GERMAN);

    @Test
    void oneRequestCarriesEveryLanguage() {
        final TestDatabase database = TestDatabase.fresh();
        Jdbi.create(database.dataSource())
                .useHandle(handle -> handle.execute("INSERT INTO smp_milestone (key) VALUES ('departure')"));
        final Inbox<BotRequest> bot = Inbox.over(database.dataSource(), BotRequest.TABLE);
        final List<String> warnings = new ArrayList<>();
        final Announcer announcer =
                new Announcer(bot, LOADED, Runnable::run, (message, failure) -> warnings.add(message));

        announcer.announce(
                BotRequest.Announce.Message.MILESTONE_BORDER,
                "departure",
                locale -> locale.getLanguage().equals("de") ? "Aufbruch" : "Departure");

        assertEquals(List.of(), warnings);
        final List<Request<BotRequest>> sent = bot.recent(BotRequest.Announce.class, 10);
        assertEquals(1, sent.size());
        // Nobody asked: a server has no Discord identity, and the network announces its own progress.
        assertEquals(Actor.STEWARD, sent.getFirst().actor());
        final BotRequest.Announce announcement =
                (BotRequest.Announce) sent.getFirst().payload();
        assertEquals("departure", announcement.milestone());
        final Map<String, String> rendered = new HashMap<>();
        for (final BotRequest.Announce.Line line : announcement.lines()) {
            rendered.put(
                    line.language(), BUNDLE.format(Locale.forLanguageTag(line.language()), announcement.text(line)));
        }
        assertEquals(
                Map.of(
                        "en", "Departure is complete. The border grows.",
                        "de", "Aufbruch ist geschafft. Die Grenze wächst."),
                rendered,
                "each language's text, the milestone named in that language, as the bot renders it");
        assertTrue(sent.getFirst().expires() != null, "a request nobody claims within the hour is dropped");
    }

    @Test
    void aRefusedWriteIsAWarning() {
        final Inbox<BotRequest> refusing = Inbox.over(TestDatabase.empty().dataSource(), BotRequest.TABLE);
        final List<String> warnings = new ArrayList<>();
        new Announcer(refusing, LOADED, Runnable::run, (message, failure) -> warnings.add(message))
                .announce(BotRequest.Announce.Message.MILESTONE, "departure", locale -> "Departure");
        assertEquals(1, warnings.size());
    }
}
