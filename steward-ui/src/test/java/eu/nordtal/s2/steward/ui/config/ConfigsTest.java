package eu.nordtal.s2.steward.ui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one value in this configuration that leaves the process: {@code public-url}.
 *
 * <p>Everything else here is checked where it is used and fails in this container. This one is
 * handed to Discord as the redirect URI, so a value that is wrong in a way this process accepts
 * comes back as {@code invalid_request} from somebody else's server - a message that points at the
 * Discord application rather than at a line of YAML on this host. That is the whole reason the
 * check is stricter than "starts with http".</p>
 */
class ConfigsTest {

    @Test
    @DisplayName("a scheme with nothing after it is not an address, however much it looks like one")
    void aSchemeIsNotAUrl() {
        // The prefix check this replaced said yes to both of these: "https://".startsWith("https://")
        // is true, and so the interface started, and the first person to sign in got Discord's
        // refusal instead of a configuration error naming the file.
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("https://"));
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("http:///path"));
    }

    @Test
    @DisplayName("a query or a fragment is refused, because the redirect URI is this plus a path")
    void aQueryIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Configs.requirePublicUrl("https://steward.example?x=1"));
        assertThrows(IllegalArgumentException.class,
                () -> Configs.requirePublicUrl("https://steward.example#top"));
    }

    @Test
    @DisplayName("a trailing slash is refused, and the message says why Discord would not match it")
    void aTrailingSlashIsRefused() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Configs.requirePublicUrl("https://steward.example/"));

        assertTrue(refused.getMessage().contains("two"), refused.getMessage());
    }

    @Test
    @DisplayName("nothing at all is its own message, not a parse error")
    void anEmptyValueSaysWhatItIsFor() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Configs.requirePublicUrl(""));

        assertTrue(refused.getMessage().contains("the address this interface answers on"),
                refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl(null));
    }

    @Test
    @DisplayName("a scheme Discord will not take is refused even though it parses")
    void onlyHttpAndHttps() {
        assertThrows(IllegalArgumentException.class,
                () -> Configs.requirePublicUrl("ftp://steward.example"));
    }

    @Test
    @DisplayName("what a deployment actually sets is accepted, port and path included")
    void theRealValuesPass() {
        assertDoesNotThrow(() -> Configs.requirePublicUrl("https://steward.dev.nordtal.eu"));
        assertDoesNotThrow(() -> Configs.requirePublicUrl("http://127.0.0.1:8080"));
        assertDoesNotThrow(() -> Configs.requirePublicUrl("https://nordtal.eu/steward"));
    }
}
