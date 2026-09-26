package eu.nordtal.s2.steward.ui.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.ui.config.UiSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the sign-in page is able to say about a deployment nobody can sign in to.
 *
 * <p>This is the half of {@link DiscordAuth} that never reaches Discord, and it is the half an
 * operator meets first: a fresh deployment has none of these three values, and the difference
 * between "you are not an admin" and "this container was never given a client id" is the
 * difference between an evening of guessing and a one-line fix. The flow itself is exercised
 * end to end in {@code StewardUiIntegrationTest}, against a stand-in Discord.</p>
 */
class DiscordAuthTest {

    @Test
    @DisplayName("each missing value is named, in the order somebody fills them in")
    void theMissingValueIsNamedRatherThanGuessedAt() {
        assertEquals("discord.client-id", missingFrom(new Values()));
        assertTrue(missingFrom(new Values().withClientId("an-application")).startsWith("discord.client-secret"));
        assertEquals(
                "discord.guild-id",
                missingFrom(new Values().withClientId("an-application").withClientSecret("shh")));
        // No admin role: who may in is the admin tree in the database, not a Discord role.
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
    @DisplayName("the redirect URI is the configured address, never the request's")
    void theRedirectUriIsWrittenDownNotGuessed() {
        // A redirect URI that follows the Host header is a redirect URI an attacker can choose.
        assertEquals(
                "https://steward.dev.nordtal.eu/auth/callback",
                new DiscordAuth(new Values(), "https://steward.dev.nordtal.eu").redirectUri());
    }

    /** The operator-facing texts that tell somebody what to type into Discord. */
    private static final List<String> GUIDANCE =
            List.of("deploy/nordtal.sh", "deploy/dev.env.example", "deploy/README.md", "README.md");

    /**
     * A path ending in {@code auth/callback}, with whatever was written in front of it that is not
     * whitespace - a scheme, a host, a placeholder like {@code <STEWARD_HOST>}.
     */
    private static final Pattern MENTION = Pattern.compile("[^\\s\"'`]*auth/callback");

    @Test
    @DisplayName("every instruction about Discord names the callback path this class actually builds")
    void theGuidanceNamesTheRealRedirectUri() {
        // season-2-ops/145. Two of these files said `/api/auth/callback` - one of them the line
        // somebody reads while REALLY installing - and `/api/<anything unrouted>` has been an
        // explicit 404 since the fallback went in. Following it means typing a URI into Discord
        // that every sign-in comes back from with `invalid_request`, and then looking for the
        // mistake in the Discord application rather than in a line of shell. The running
        // installation was only fine because it was filled in by hand.
        //
        // The path is derived from DiscordAuth rather than written out here: this test has to fail
        // when the route moves, not when somebody remembers to update a string in two places.
        final String built = new DiscordAuth(new Values(), "https://steward.example").redirectUri();
        final String path = built.substring("https://steward.example".length());

        final List<String> wrong = new ArrayList<>();
        for (final String file : GUIDANCE) {
            final Matcher mention = MENTION.matcher(read(repository().resolve(file)));
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

    /** What is left of a mention once a scheme and a host are taken off the front. */
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

    /**
     * The repository root, found rather than assumed - a test's working directory is its module.
     */
    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        assertTrue(
                directory != null, "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        return directory;
    }

    private static String missingFrom(final UiSpec.DiscordSpec values) {
        return new DiscordAuth(values, "https://steward.example")
                .whatIsMissing()
                .orElseThrow();
    }

    /** The three values, each empty until a test fills it in. */
    private static final class Values implements UiSpec.DiscordSpec {

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
    @DisplayName("a plaintext API base that is not this machine is refused")
    void cleartextGoesNowhereButHere() {
        final Values config = new Values();

        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscordAuth(config, "https://steward.example", "http://discord.example"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscordAuth(config, "https://steward.example", "http://10.0.0.5:8080"));

        // And the two that are allowed: the real one, and the stand-in every test here uses.
        assertDoesNotThrow(() -> new DiscordAuth(config, "https://steward.example", DiscordAuth.DISCORD_API));
        assertDoesNotThrow(() -> new DiscordAuth(config, "https://steward.example", "http://127.0.0.1:18093"));
    }
}
