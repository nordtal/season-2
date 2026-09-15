package eu.nordtal.s2.steward.ui.auth;

import eu.nordtal.s2.steward.ui.config.UiSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the sign-in page is able to say about a deployment nobody can sign in to.
 *
 * <p>This is the half of {@link DiscordAuth} that never reaches Discord, and it is the half an
 * operator meets first: a fresh deployment has none of these four values, and the difference
 * between "you are not an admin" and "this container was never given a client id" is the
 * difference between an evening of guessing and a one-line fix. The flow itself is exercised
 * end to end in {@code StewardUiIntegrationTest}, against a stand-in Discord.</p>
 */
class DiscordAuthTest {

    @Test
    @DisplayName("each missing value is named, in the order somebody fills them in")
    void theMissingValueIsNamedRatherThanGuessedAt() {
        assertEquals("discord.client-id", missingFrom(new Values()));
        assertTrue(missingFrom(new Values().withClientId("an-application"))
                .startsWith("discord.client-secret"));
        assertEquals("discord.guild-id", missingFrom(new Values()
                .withClientId("an-application").withClientSecret("shh")));
        assertEquals("discord.admin-role", missingFrom(new Values()
                .withClientId("an-application").withClientSecret("shh").withGuildId("1234")));
    }

    @Test
    @DisplayName("an empty admin role is nobody, never everybody")
    void anEmptyRoleDoesNotOpenTheDoor() {
        // The one default that would be catastrophic to get the other way round: an interface that
        // can stop a server and read a token, opened by forgetting a value.
        final Values all = new Values().withClientId("an-application").withClientSecret("shh")
                .withGuildId("1234");

        assertEquals("discord.admin-role", missingFrom(all));
        assertTrue(new DiscordAuth(all.withAdminRole("4711"), "https://steward.example")
                .whatIsMissing().isEmpty());
    }

    @Test
    @DisplayName("the redirect URI is the configured address, never the request's")
    void theRedirectUriIsWrittenDownNotGuessed() {
        // A redirect URI that follows the Host header is a redirect URI an attacker can choose.
        assertEquals("https://steward.dev.nordtal.eu/auth/callback",
                new DiscordAuth(new Values(), "https://steward.dev.nordtal.eu").redirectUri());
    }

    private static String missingFrom(final UiSpec.DiscordSpec values) {
        return new DiscordAuth(values, "https://steward.example").whatIsMissing().orElseThrow();
    }

    /** The four values, each empty until a test fills it in. */
    private static final class Values implements UiSpec.DiscordSpec {

        private String clientId = "";
        private String clientSecret = "";
        private String guildId = "";
        private String adminRole = "";

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

        Values withAdminRole(final String value) {
            adminRole = value;
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

        @Override
        public String adminRole() {
            return adminRole;
        }
    }

    @Test
    @DisplayName("a plaintext API base that is not this machine is refused")
    void cleartextGoesNowhereButHere() {
        final Values config = new Values();

        assertThrows(IllegalArgumentException.class,
                () -> new DiscordAuth(config, "https://steward.example", "http://discord.example"));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscordAuth(config, "https://steward.example", "http://10.0.0.5:8080"));

        // And the two that are allowed: the real one, and the stand-in every test here uses.
        assertDoesNotThrow(() ->
                new DiscordAuth(config, "https://steward.example", DiscordAuth.DISCORD_API));
        assertDoesNotThrow(() ->
                new DiscordAuth(config, "https://steward.example", "http://127.0.0.1:18093"));
    }
}
