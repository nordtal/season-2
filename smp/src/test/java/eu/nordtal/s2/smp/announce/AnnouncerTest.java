package eu.nordtal.s2.smp.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.DatabaseMessages;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.MilestoneContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One request in the bot's inbox per announcement, with every language's message, which the bot renders. */
class AnnouncerTest {

    private static final List<Locale> LOADED = List.of(Locale.ENGLISH, Locale.GERMAN);
    private static final Messages BUNDLE =
            Messages.load(AnnouncerTest.class.getClassLoader(), "messages/database", Locale.GERMAN);

    @Test
    void oneRequestCarriesEveryLanguage() {
        final Inbox<BotRequest> bot = Inbox.over(TestDatabase.fresh().dataSource(), BotRequest.TABLE);
        final List<String> warnings = new ArrayList<>();
        final Announcer announcer =
                new Announcer(bot, LOADED, Runnable::run, (message, failure) -> warnings.add(message));

        announcer.announce(locale -> border(locale.getLanguage().equals("de") ? "Aufbruch" : "Departure"));

        assertEquals(List.of(), warnings);
        final List<Request<BotRequest>> sent = bot.recent(BotRequest.Announce.class, 10);
        assertEquals(1, sent.size());
        // Nobody asked: a server has no Discord identity, and the network announces its own progress.
        assertEquals(Actor.STEWARD, sent.getFirst().actor());
        final Map<String, String> rendered = new java.util.HashMap<>();
        ((BotRequest.Announce) sent.getFirst().payload())
                .messages()
                .forEach((tag, message) -> rendered.put(tag, BUNDLE.format(Locale.forLanguageTag(tag), message)));
        assertEquals(
                Map.of(
                        "en", "Departure is complete - the border grows.",
                        "de", "Aufbruch ist geschafft - die Grenze wächst."),
                rendered,
                "each language's message, its values in that language, as the bot renders it");
        assertTrue(sent.getFirst().expires() != null, "a request nobody claims within the hour is dropped");
    }

    @Test
    void aRefusedWriteIsAWarning() {
        final Inbox<BotRequest> refusing = Inbox.over(TestDatabase.empty().dataSource(), BotRequest.TABLE);
        final List<String> warnings = new ArrayList<>();
        new Announcer(refusing, LOADED, Runnable::run, (message, failure) -> warnings.add(message))
                .announce(locale ->
                        DatabaseMessages.MESSAGES.announcement().milestone(new MilestoneContext("Departure")));
        assertEquals(1, warnings.size());
    }

    private static eu.nordtal.s2.messages.MessageRef border(final String milestone) {
        return DatabaseMessages.MESSAGES.announcement().milestoneSection().border(new MilestoneContext(milestone));
    }
}
