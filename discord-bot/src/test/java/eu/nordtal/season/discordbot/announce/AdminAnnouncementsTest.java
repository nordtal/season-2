package eu.nordtal.season.discordbot.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.messages.document.Document;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class AdminAnnouncementsTest {

    private static final Document HELLO = Document.decode("""
            {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Hello", "de": "Hallo"}}]}]}""");

    /** The channels of the guild the bot can see, and the one Discord refuses a post in. */
    private static final Map<String, String> GUILD = Map.of("111", "ankuendigungen", "222", "news", "444", "closed");

    private static final Logger LOG = (Logger) Proxy.newProxyInstance(
            Logger.class.getClassLoader(),
            new Class<?>[] {Logger.class},
            (proxy, method, arguments) -> method.getName().startsWith("is") ? false : null);

    private final List<String> sent = new ArrayList<>();
    private final List<String> noted = new ArrayList<>();

    private final Announcements channels = new Announcements(
            new Announcements.Channels() {
                @Override
                public Optional<String> name(final String channelId) {
                    return Optional.ofNullable(GUILD.get(channelId));
                }

                @Override
                public void send(final String channelId, final String text) {
                    sent.add(channelId + ": " + text);
                }

                @Override
                public void send(final String channelId, final MessageCreateData message) throws ExecutionException {
                    if (channelId.equals("444")) {
                        throw new ExecutionException(new IllegalStateException("missing access"));
                    }
                    sent.add(channelId + ": " + message.getContent());
                }
            },
            GuildLanguages.of(
                    List.of(new GuildLanguages.Language("en", "", "", "", "", "", "222")),
                    new Languages(List.of("en"))),
            LOG);

    private AdminAnnouncements subject(final Optional<Document> document) {
        return new AdminAnnouncements(
                id -> document,
                (drawn, language) -> MessageCreateData.fromContent(
                        language + " " + drawn.parts().size()),
                channels,
                (id, languages) -> noted.add(id + " " + languages),
                LOG);
    }

    @Test
    void eachLanguageGoesIntoTheChannelTheDraftChoseAndOnlyThePostedOnesAreNoted() {
        final BotRequest.PostAnnouncement.Answer answer = subject(Optional.of(HELLO))
                .post(new BotRequest.PostAnnouncement(
                        7,
                        List.of(
                                new BotRequest.PostAnnouncement.Target("de", "111"),
                                new BotRequest.PostAnnouncement.Target("en", "333"))));

        assertEquals(List.of("111: de 1"), sent, "the bot cannot see 333");
        assertEquals(List.of("7 [de]"), noted);
        assertEquals(
                new BotRequest.PostAnnouncement.Answer(List.of(
                        new BotRequest.Announce.Posted("de", true), new BotRequest.Announce.Posted("en", false))),
                answer);
    }

    @Test
    void aChannelThatRefusesThePostFailsThatLanguageAlone() {
        final BotRequest.PostAnnouncement.Answer answer = subject(Optional.of(HELLO))
                .post(new BotRequest.PostAnnouncement(
                        7,
                        List.of(
                                new BotRequest.PostAnnouncement.Target("de", "444"),
                                new BotRequest.PostAnnouncement.Target("en", "222"))));

        assertEquals(List.of("222: en 1"), sent);
        assertEquals(
                List.of(new BotRequest.Announce.Posted("de", false), new BotRequest.Announce.Posted("en", true)),
                answer.targets());
    }

    @Test
    void anAnnouncementThatIsGonePostsNothing() {
        final BotRequest.PostAnnouncement.Answer answer = subject(Optional.empty())
                .post(new BotRequest.PostAnnouncement(7, List.of(new BotRequest.PostAnnouncement.Target("en", "222"))));

        assertEquals(List.of(), sent);
        assertEquals(List.of(), noted);
        assertEquals(List.of(new BotRequest.Announce.Posted("en", false)), answer.targets());
    }
}
