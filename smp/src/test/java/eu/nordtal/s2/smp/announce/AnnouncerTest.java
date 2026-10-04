package eu.nordtal.s2.smp.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.smp.SmpMessages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One request in the bot's inbox per announcement, with every language's text rendered from the real bundle. */
class AnnouncerTest {

    private static final Messages MESSAGES =
            Messages.load(AnnouncerTest.class.getClassLoader(), "messages/smp", Locale.GERMAN, Locale.ENGLISH);

    @Test
    void oneRequestCarriesEveryLanguage() {
        final Inbox<BotRequest> bot = Inbox.over(TestDatabase.fresh().dataSource(), BotRequest.TABLE);
        final List<String> warnings = new ArrayList<>();
        final Announcer announcer = new Announcer(
                bot, MessageRenderer.of(MESSAGES), Runnable::run, (message, failure) -> warnings.add(message));

        announcer.announce(locale -> SmpMessages.MESSAGES
                .smp()
                .announce()
                .milestoneSection()
                .border(new MilestoneContext(locale.getLanguage().equals("de") ? "Aufbruch" : "Departure")));

        assertEquals(List.of(), warnings);
        final List<Request<BotRequest>> sent = bot.recent(BotRequest.Announce.class, 10);
        assertEquals(1, sent.size());
        // Nobody asked: a server has no Discord identity, and the network announces its own progress.
        assertEquals(Actor.STEWARD, sent.getFirst().actor());
        assertEquals(
                new BotRequest.Announce(Map.of(
                        "de", "Aufbruch ist geschafft - die Grenze wächst.",
                        "en", "Departure is complete - the border grows.")),
                sent.getFirst().payload());
        assertTrue(sent.getFirst().expires() != null, "a request nobody claims within the hour is dropped");
    }

    @Test
    void aRefusedWriteIsAWarning() {
        final Inbox<BotRequest> refusing = Inbox.over(TestDatabase.empty().dataSource(), BotRequest.TABLE);
        final List<String> warnings = new ArrayList<>();
        new Announcer(
                        refusing,
                        MessageRenderer.of(MESSAGES),
                        Runnable::run,
                        (message, failure) -> warnings.add(message))
                .announce(SmpMessages.MESSAGES.smp().announce().milestone(new MilestoneContext("Departure")));
        assertEquals(1, warnings.size());
    }
}
