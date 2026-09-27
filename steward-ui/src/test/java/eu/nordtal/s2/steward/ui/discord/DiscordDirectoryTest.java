package eu.nordtal.s2.steward.ui.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The list of names behind the ids, against a stand-in Discord.
 *
 * What is worth testing here is not that Gson can read a JSON array. It is the four things that
 * decide whether a configuration page is usable or misleading: that a deployment with no token says
 * which value is missing rather than drawing an empty select; that {@code @everyone} is not offered
 * as a role somebody could give away; that the header is {@code Bot} and not {@code Bearer}, which
 * Discord answers 401 for with no hint that the prefix was the problem; and that a second call
 * inside the cache window does not become a second request, because a page with eleven pickers on
 * it would otherwise be eleven.
 */
class DiscordDirectoryTest {

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
                new DiscordDirectory(new Values().withGuildId("1"), "https://discord.invalid");

        final String reason = directory.unavailable();

        assertNotNull(reason);
        assertTrue(reason.contains("discord.bot-token"), reason);
    }

    @Test
    void withoutAGuildItSaysSo() {
        assertTrue(new DiscordDirectory(new Values().withBotToken("t"), "https://discord.invalid")
                .unavailable()
                .contains("discord.guild-id"));
    }

    @Test
    void withBothItIsAvailable() throws Exception {
        assertNull(directoryFor("[]").unavailable());
    }

    @Test
    void everyoneIsNotARoleAnybodyMeansToConfigure() throws Exception {
        // @everyone carries the guild's own id and pinging it is a thing done by accident exactly once.
        final DiscordDirectory directory = directoryFor("""
                [{"id":"1","name":"@everyone","position":0},
                 {"id":"20","name":"Donor","position":3},
                 {"id":"30","name":"Admin","position":9}]""");

        final List<DiscordDirectory.Entry> roles = directory.roles();

        assertEquals(
                List.of("Admin", "Donor"),
                roles.stream().map(DiscordDirectory.Entry::name).toList());
    }

    @Test
    void theTokenIsSentWithDiscordsOwnScheme() throws Exception {
        directoryFor("[]").roles();

        assertEquals(List.of("Bot a-token"), authorizations);
    }

    @Test
    void oneAnswerServesAWholePageOfPickers() throws Exception {
        final DiscordDirectory directory = directoryFor("[]");

        directory.roles();
        directory.roles();
        directory.roles();

        assertEquals(1, authorizations.size());
    }

    @Test
    void aRefusalIsTranslatedRatherThanForwarded() throws Exception {
        final DiscordDirectory directory = directoryFor(401, "{\"message\":\"401: Unauthorized\"}");

        final DiscordDirectory.DirectoryException failure =
                assertThrows(DiscordDirectory.DirectoryException.class, directory::roles);

        assertTrue(failure.getMessage().contains("bot token"), failure.getMessage());
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
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The four values this class reads, each empty until a test fills it in. */
    private static final class Values implements UiSpec.DiscordSpec {

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
