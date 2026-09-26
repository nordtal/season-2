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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The list of names behind the ids, against a stand-in Discord.
 *
 * <p>What is worth testing here is not that Gson can read a JSON array. It is the four things that
 * decide whether a configuration page is usable or misleading: that a deployment with no token says
 * which value is missing rather than drawing an empty select; that {@code @everyone} is not offered
 * as a role somebody could give away; that the header is {@code Bot} and not {@code Bearer}, which
 * Discord answers 401 for with no hint that the prefix was the problem; and that a second call
 * inside the cache window does not become a second request, because a page with eleven pickers on
 * it would otherwise be eleven.</p>
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
    @DisplayName("no token is a sentence naming the setting, not an empty list")
    void withoutATokenItSaysWhichValueIsMissing() {
        // An empty picker and an unreachable Discord look identical, and only one of them is
        // something the person looking at the page can fix in ten seconds.
        final DiscordDirectory directory =
                new DiscordDirectory(new Values().withGuildId("1"), "https://discord.invalid");

        final String reason = directory.unavailable();

        assertNotNull(reason);
        assertTrue(reason.contains("discord.bot-token"), reason);
    }

    @Test
    @DisplayName("no guild is a different sentence, because it is a different mistake")
    void withoutAGuildItSaysSo() {
        assertTrue(new DiscordDirectory(new Values().withBotToken("t"), "https://discord.invalid")
                .unavailable()
                .contains("discord.guild-id"));
    }

    @Test
    @DisplayName("with both, it stops complaining and asks")
    void withBothItIsAvailable() throws Exception {
        assertNull(directoryFor("[]").unavailable());
    }

    @Test
    @DisplayName("@everyone is not offered as a role, and the rest come highest first")
    void everyoneIsNotARoleAnybodyMeansToConfigure() throws Exception {
        // @everyone carries the guild's own id. Giving it to somebody is a no-op and pinging it is
        // a thing to do by accident exactly once.
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
    @DisplayName("the token travels as Bot, which is not Bearer")
    void theTokenIsSentWithDiscordsOwnScheme() throws Exception {
        directoryFor("[]").roles();

        assertEquals(List.of("Bot a-token"), authorizations);
    }

    @Test
    @DisplayName("a second question inside the cache window is not a second request")
    void oneAnswerServesAWholePageOfPickers() throws Exception {
        final DiscordDirectory directory = directoryFor("[]");

        directory.roles();
        directory.roles();
        directory.roles();

        assertEquals(1, authorizations.size());
    }

    @Test
    @DisplayName("a refusal from Discord is named and its body is not passed on")
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
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
