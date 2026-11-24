package eu.nordtal.season.discordbot.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.messages.document.Documents;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

class AnnouncementsTest {

    private static final Map<String, String> GUILD = Map.of("111", "news", "222", "neuigkeiten");

    record Line(String level, String text) {}

    private final List<Line> logged = new ArrayList<>();
    private final List<MessageCreateData> messages = new ArrayList<>();
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
        public void send(final String channelId, final MessageCreateData message) {
            messages.add(message);
            sent.add(channelId + ": " + message.getContent());
        }
    };

    private final GuildLanguages languages = GuildLanguages.of(
            List.of(
                    new GuildLanguages.Language("en", "", "", "", "", "111"),
                    new GuildLanguages.Language("de", "", "", "", "", "222")),
            new eu.nordtal.season.common.language.Languages(List.of("en", "de")));

    private final Announcements subject = new Announcements(channels, languages, log);

    @Test
    void everyPostedLineIsLoggedAtInfoWithItsLanguageAndChannel() {
        assertTrue(post("en", BotRequest.Announce.words(Map.of("en", "Season starts today."))));
        assertTrue(post("de", BotRequest.Announce.words(Map.of("de", "Die Saison startet heute."))));

        assertEquals(List.of("111: Season starts today.", "222: Die Saison startet heute."), sent);
        final List<String> info = logged.stream()
                .filter(line -> line.level().equals("info"))
                .map(Line::text)
                .toList();
        assertEquals(2, info.size(), () -> "logged: " + logged);
        assertTrue(info.get(0).contains("language=en channel=111 channel_name=\"news\""), info.get(0));
        assertTrue(info.get(1).contains("language=de channel=222 channel_name=\"neuigkeiten\""), info.get(1));
    }

    @Test
    void announcementsFromTheInboxSuppressAllMentions() {
        assertTrue(post("en", BotRequest.Announce.words(Map.of("en", "Server announcement"))));
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().getAllowedMentions().isEmpty());
    }

    @Test
    void anAdminsWordsKeepTheirMarkdown() {
        assertTrue(post("en", BotRequest.Announce.words(Map.of("en", "The **End** opens at 8_pm."))));

        assertEquals(List.of("111: The **End** opens at 8_pm."), sent);
    }

    @Test
    void anAdminsWordsLongerThanAMessageAreCutToFitOne() {
        assertTrue(post("en", BotRequest.Announce.words(Map.of("en", "a".repeat(2001)))));

        assertEquals(List.of("111: " + "a".repeat(1999) + "…"), sent);
    }

    @Test
    void retiredMilestoneAnnouncementsAreNotPostedAndHaveNoDocuments() {
        for (final BotRequest.Announce.Message message : List.of(
                BotRequest.Announce.Message.MILESTONE,
                BotRequest.Announce.Message.MILESTONE_BORDER,
                BotRequest.Announce.Message.MILESTONE_NETHER,
                BotRequest.Announce.Message.MILESTONE_END)) {
            assertFalse(post("en", BotRequest.Announce.milestone(message, "a.milestone", Map.of("en", "Deep"))));
        }
        assertEquals(List.of(), sent);
        final var packaged = Documents.packaged(Announcements.class.getClassLoader(), StoredDocuments.NAMESPACE);
        for (final String name :
                List.of("announce-milestone", "announce-border", "announce-nether", "announce-end", "announce-words")) {
            assertFalse(packaged.containsKey(name), name);
        }
    }

    @Test
    void aLanguageWithoutAChannelIsNotPosted() {
        assertFalse(
                post("fr", BotRequest.Announce.words(Map.of("fr", "Bonjour"))), "nothing is posted where nobody reads");
        assertEquals(List.of(), sent);
    }

    private boolean post(final String languageTag, final BotRequest.Announce announcement) {
        return Announcements.text(announcement, announcement.lines().getFirst())
                .map(text -> subject.post(languageTag, text))
                .orElse(false);
    }
}
