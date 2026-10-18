package eu.nordtal.season.steward.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.steward.config.WebSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Tests the messages for a deployment that nobody can sign in to, before any call to Discord. */
class DiscordAuthTest {

    @Test
    void theMissingValueIsNamedRatherThanGuessedAt() {
        assertEquals("discord.client-id", missingFrom(new Values()));
        assertTrue(missingFrom(new Values().withClientId("an-application")).startsWith("discord.client-secret"));
        assertEquals(
                "discord.guild-id",
                missingFrom(new Values().withClientId("an-application").withClientSecret("shh")));
        // The admin tree in the database decides who may sign in, not a Discord role.
        assertTrue(new DiscordAuth(
                        new Values()
                                .withClientId("an-application")
                                .withClientSecret("shh")
                                .withGuildId("1234"),
                        "https://steward.example")
                .whatIsMissing()
                .isEmpty());
    }

    @Test
    void theRedirectUriIsWrittenDownNotGuessed() {
        // The redirect URI must not follow the Host header.
        assertEquals(
                "https://steward.dev.nordtal.eu/auth/callback",
                new DiscordAuth(new Values(), "https://steward.dev.nordtal.eu").redirectUri());
    }

    private static final List<String> GUIDANCE =
            List.of("deploy/nordtal.sh", "deploy/dev.env.example", "deploy/README.md", "README.md");

    private static final Pattern MENTION = Pattern.compile("[^\\s\"'`]*auth/callback");

    @Test
    void theGuidanceNamesTheRealRedirectUri() {
        // The path derives from DiscordAuth, so the test fails when the route moves.
        final String built = new DiscordAuth(new Values(), "https://steward.example").redirectUri();
        final String path = built.substring("https://steward.example".length());

        final List<String> wrong = new ArrayList<>();
        for (final String file : GUIDANCE) {
            final Matcher mention = MENTION.matcher(read(RepositoryRoot.path().resolve(file)));
            while (mention.find()) {
                final String named = pathOf(mention.group());
                if (!named.equals(path)) {
                    wrong.add(file + " says " + named);
                }
            }
        }

        assertEquals(
                List.of(),
                wrong,
                "the sign-in redirects to " + path
                        + ", and an instruction that names anything else sends an operator to Discord with"
                        + " a URI the sign-in will never present");
    }

    /** Removes the scheme and host from the front of a mention. */
    private static String pathOf(final String mention) {
        final String withoutScheme = mention.replaceFirst("^https?://", "");
        final int host = withoutScheme.indexOf('/');
        return host < 0 ? "/" + withoutScheme : withoutScheme.substring(host);
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String missingFrom(final WebSpec.DiscordSpec values) {
        return new DiscordAuth(values, "https://steward.example")
                .whatIsMissing()
                .orElseThrow();
    }

    /** Holds the three values, each empty until a test sets it. */
    private static final class Values implements WebSpec.DiscordSpec {

        private String clientId = "";
        private String clientSecret = "";
        private String guildId = "";

        Values withClientId(final String value) {
            clientId = value;
            return this;
        }

        Values withClientSecret(final String value) {
            clientSecret = value;
            return this;
        }

        Values withGuildId(final String value) {
            guildId = value;
            return this;
        }

        @Override
        public String clientId() {
            return clientId;
        }

        @Override
        public String clientSecret() {
            return clientSecret;
        }

        @Override
        public String guildId() {
            return guildId;
        }
    }

    @Test
    void cleartextGoesNowhereButHere() {
        final Values config = new Values();

        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscordAuth(config, "https://steward.example", "http://discord.example"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscordAuth(config, "https://steward.example", "http://10.0.0.5:8080"));

        // Allowed are the real API and a stand-in on a loopback port.
        assertDoesNotThrow(() -> new DiscordAuth(config, "https://steward.example", DiscordAuth.DISCORD_API));
        assertDoesNotThrow(() -> new DiscordAuth(config, "https://steward.example", "http://127.0.0.1:18093"));
    }
}
