package eu.nordtal.s2.discordbot.announce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.discordbot.config.Languages;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

/** What the bot's log says about an announcement it posted. */
class AnnouncementsTest {

    private static final Map<String, String> GUILD = Map.of("111", "announcements", "222", "ankuendigungen");

    /** One logged line, with its level and its placeholders filled in. */
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

    private final Languages languages = Languages.of(List.of(
            new Languages.Language("en", "", "", "", "", "", "111"),
            new Languages.Language("de", "", "", "", "", "", "222")));

    private final Announcements subject = new Announcements(channels, languages, log);

    @Test
    void everyPostedLineIsLoggedAtInfoWithItsLanguageAndChannel() {
        assertTrue(subject.post("en", "Frontier is complete"));
        assertTrue(subject.post("de", "Grenzland ist geschafft"));

        assertEquals(List.of("111: Frontier is complete", "222: Grenzland ist geschafft"), sent);
        final List<String> info = logged.stream()
                .filter(line -> line.level().equals("info"))
                .map(Line::text)
                .toList();
        assertEquals(2, info.size(), () -> "logged: " + logged);
        assertTrue(info.get(0).contains("'en'") && info.get(0).contains("#announcements (111)"), info.get(0));
        assertTrue(info.get(1).contains("'de'") && info.get(1).contains("#ankuendigungen (222)"), info.get(1));
    }
}
