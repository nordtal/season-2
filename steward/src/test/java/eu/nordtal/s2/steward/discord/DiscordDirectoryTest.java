package eu.nordtal.s2.steward.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.steward.config.WebSpec;
import eu.nordtal.s2.steward.texts.WebTexts;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The names behind the ids, against a stand-in Discord.
 *
 * A missing token is named, the channels come in the guild's order, the header is {@code Bot}, and the cache holds.
 */
class DiscordDirectoryTest {

    private static final Messages TEXTS = WebTexts.load().messages();

    private HttpServer server;
    private final List<String> authorizations = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void withoutATokenItSaysWhichValueIsMissing() {
        // An empty picker and an unreachable Discord look identical; only one is fixable in ten seconds.
        final DiscordDirectory directory =
                new DiscordDirectory(new Values().withGuildId("1"), "https://discord.invalid", Clock.systemUTC());

        final String reason = english(directory.unavailable());

        assertTrue(reason.contains("discord.bot-token"), reason);
    }

    @Test
    void withoutAGuildItSaysSo() {
        final String reason = english(
                new DiscordDirectory(new Values().withBotToken("t"), "https://discord.invalid", Clock.systemUTC())
                        .unavailable());

        assertTrue(reason.contains("discord.guild-id"), reason);
    }

    @Test
    void withBothItIsAvailable() throws Exception {
        assertNull(directoryFor("[]").unavailable());
    }

    @Test
    void theChannelsComeInTheGuildsOwnOrder() throws Exception {
        final DiscordDirectory directory = directoryFor("""
                [{"id":"30","name":"talk","position":9,"type":0},
                 {"id":"20","name":"Lounge","position":3,"type":2},
                 {"id":"10","name":"Text","position":0,"type":4}]""");

        final List<DiscordDirectory.Entry> channels = directory.channels();

        assertEquals(
                List.of("Text", "Lounge", "talk"),
                channels.stream().map(DiscordDirectory.Entry::name).toList());
    }

    @Test
    void theTokenIsSentWithDiscordsOwnScheme() throws Exception {
        directoryFor("[]").channels();

        assertEquals(List.of("Bot a-token"), authorizations);
    }

    @Test
    void oneAnswerServesAWholePageOfPickers() throws Exception {
        final DiscordDirectory directory = directoryFor("[]");

        directory.channels();
        directory.channels();
        directory.channels();

        assertEquals(1, authorizations.size());
    }

    @Test
    void aRefusalIsTranslatedRatherThanForwarded() throws Exception {
        final DiscordDirectory directory = directoryFor(401, "{\"message\":\"401: Unauthorized\"}");

        final DiscordDirectory.DirectoryException failure =
                assertThrows(DiscordDirectory.DirectoryException.class, directory::channels);

        assertEquals("Discord refused the bot token. Check discord.bot-token.", english(failure.why()));
    }

    private static String english(final @Nullable MessageRef message) {
        assertNotNull(message);
        return TEXTS.format(Locales.DEFAULT, message);
    }

    private DiscordDirectory directoryFor(final String body) throws IOException {
        return directoryFor(200, body);
    }

    private DiscordDirectory directoryFor(final int status, final String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return new DiscordDirectory(
                new Values().withGuildId("1").withBotToken("a-token"),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Clock.systemUTC());
    }

    /** The four values this class reads, each empty until a test fills it in. */
    private static final class Values implements WebSpec.DiscordSpec {

        private String guildId = "";
        private String botToken = "";

        Values withGuildId(final String value) {
            guildId = value;
            return this;
        }

        Values withBotToken(final String value) {
            botToken = value;
            return this;
        }

        @Override
        public String guildId() {
            return guildId;
        }

        @Override
        public String botToken() {
            return botToken;
        }
    }
}
