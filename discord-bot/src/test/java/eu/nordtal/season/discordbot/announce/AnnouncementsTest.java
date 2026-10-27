package eu.nordtal.season.discordbot.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

class AnnouncementsTest {

    private static final DocumentRenderer DOCUMENTS = StoredDocumentsStandIn.renderer();

    private static final Map<String, String> GUILD = Map.of("111", "announcements", "222", "ankuendigungen");

    record Line(String level, String text) {}

    private final List<Line> logged = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();

    private final Logger log = (Logger) Proxy.newProxyInstance(
            Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, arguments) -> {
                final String level = method.getName();
                if (level.startsWith("is")) {
                    return true;
                }
                if (arguments != null && arguments.length > 0 && arguments[0] instanceof final String format) {
                    final Object[] rest = Arrays.copyOfRange(arguments, 1, arguments.length);
                    final Object[] flat =
                            rest.length == 1 && rest[0] instanceof final Object[] varargs ? varargs : rest;
                    logged.add(new Line(
                            level, MessageFormatter.arrayFormat(format, flat).getMessage()));
                }
                return method.getReturnType() == String.class ? "test" : null;
            });

    private final Announcements.Channels channels = new Announcements.Channels() {
        @Override
        public Optional<String> name(final String channelId) {
            return Optional.ofNullable(GUILD.get(channelId));
        }

        @Override
        public void send(final String channelId, final String text) {
            sent.add(channelId + ": " + text);
        }
    };

    private final GuildLanguages languages = GuildLanguages.of(
            List.of(
                    new GuildLanguages.Language("en", "", "", "", "", "", "111"),
                    new GuildLanguages.Language("de", "", "", "", "", "", "222")),
            new eu.nordtal.season.common.language.Languages(List.of("en", "de")));

    private final Announcements subject = new Announcements(channels, languages, log);

    @Test
    void everyPostedLineIsLoggedAtInfoWithItsLanguageAndChannel() {
        assertTrue(post("en", milestone(BotRequest.Announce.Message.MILESTONE_BORDER, "en", "Frontier")));
        assertTrue(post("de", milestone(BotRequest.Announce.Message.MILESTONE_BORDER, "de", "Grenzland")));

        assertEquals(
                List.of(
                        "111: Frontier is complete. The border grows.",
                        "222: Grenzland ist geschafft. Die Grenze wächst."),
                sent);
        final List<String> info = logged.stream()
                .filter(line -> line.level().equals("info"))
                .map(Line::text)
                .toList();
        assertEquals(2, info.size(), () -> "logged: " + logged);
        assertTrue(info.get(0).contains("'en'") && info.get(0).contains("#announcements (111)"), info.get(0));
        assertTrue(info.get(1).contains("'de'") && info.get(1).contains("#ankuendigungen (222)"), info.get(1));
    }

    @Test
    void anAdminsWordsKeepTheirMarkdownAndANameDoesNot() {
        assertTrue(post("en", BotRequest.Announce.words(Map.of("en", "The **End** opens at 8_pm."))));
        assertTrue(post("en", milestone(BotRequest.Announce.Message.MILESTONE, "en", "Far_Side")));

        assertEquals(List.of("111: The **End** opens at 8_pm.", "111: Far\\_Side is complete."), sent);
    }

    @Test
    void aLanguageWithoutAChannelIsNotPosted() {
        assertFalse(
                post("fr", BotRequest.Announce.words(Map.of("fr", "Bonjour"))), "nothing is posted where nobody reads");
        assertEquals(List.of(), sent);
    }

    @Test
    void eachMilestoneIsPostedFromItsOwnDocument() {
        for (final BotRequest.Announce.Message message :
                List.of(BotRequest.Announce.Message.MILESTONE_NETHER, BotRequest.Announce.Message.MILESTONE_END)) {
            assertTrue(post("en", milestone(message, "en", "Deep")));
        }

        assertEquals(
                List.of("111: Deep is complete. The Nether is open.", "111: Deep is complete. The End is open."), sent);
    }

    private static BotRequest.Announce milestone(
            final BotRequest.Announce.Message message, final String language, final String name) {
        return BotRequest.Announce.milestone(message, "a.milestone", Map.of(language, name));
    }

    /** Posts the one line of an announcement as the bot posts what Steward or the game asks for. */
    private boolean post(final String languageTag, final BotRequest.Announce announcement) {
        return subject.post(
                languageTag,
                Announcements.text(DOCUMENTS, announcement, announcement.lines().getFirst()));
    }
}
