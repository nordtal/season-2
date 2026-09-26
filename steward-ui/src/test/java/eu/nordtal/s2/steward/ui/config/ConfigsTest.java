package eu.nordtal.s2.steward.ui.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("https://steward.example?x=1"));
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("https://steward.example#top"));
    }

    @Test
    @DisplayName("a trailing slash is refused, and the message says why Discord would not match it")
    void aTrailingSlashIsRefused() {
        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> Configs.requirePublicUrl("https://steward.example/"));

        assertTrue(refused.getMessage().contains("two"), refused.getMessage());
    }

    @Test
    @DisplayName("nothing at all is its own message, not a parse error")
    void anEmptyValueSaysWhatItIsFor() {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl(""));

        assertTrue(refused.getMessage().contains("the address this interface answers on"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl(null));
    }

    @Test
    @DisplayName("a scheme Discord will not take is refused even though it parses")
    void onlyHttpAndHttps() {
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("ftp://steward.example"));
    }

    @Test
    @DisplayName("what a deployment actually sets is accepted, port and path included")
    void theRealValuesPass() {
        assertDoesNotThrow(() -> Configs.requirePublicUrl("https://steward.dev.nordtal.eu"));
        assertDoesNotThrow(() -> Configs.requirePublicUrl("http://127.0.0.1:8080"));
        assertDoesNotThrow(() -> Configs.requirePublicUrl("https://nordtal.eu/steward"));
    }

    // --- the relying party, held against the address the browser actually uses -----------------

    @Test
    @DisplayName("a relying party the public address is not under is refused, and the message names both")
    void aRelyingPartyMustBeTheAddressOrAParentOfIt() {
        // The production pair, and the two shapes of it that are correct.
        Configs.requireRelyingParty("nordtal.eu", "https://steward.dev.nordtal.eu");
        Configs.requireRelyingParty("nordtal.eu", "https://nordtal.eu");
        Configs.requireRelyingParty("steward.dev.nordtal.eu", "https://steward.dev.nordtal.eu");

        // A NEAR MISS THAT LOOKS RIGHT IN A DIFF. `nordtal.eu` and `ordtal.eu` differ by a
        // character, and the naive check - endsWith - accepts the second for the first. A browser
        // would refuse every ceremony in silence, so the dot is part of the comparison here.
        final IllegalArgumentException wrong = assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("ordtal.eu", "https://steward.dev.nordtal.eu"));
        assertTrue(
                wrong.getMessage().contains("ordtal.eu") && wrong.getMessage().contains("steward.dev.nordtal.eu"),
                "the message has to name both values, or nobody can see what does not match: " + wrong.getMessage());

        // A BARE TLD IS A SUFFIX OF THE HOST, and the naive check therefore accepts it - measured,
        // which is why the implementation has a second condition. A browser refuses a relying
        // party id that is a public suffix, in silence, because every site under one would share a
        // set of keys.
        final IllegalArgumentException tld = assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("eu", "https://steward.dev.nordtal.eu"));
        assertTrue(tld.getMessage().contains("registrable"), tld.getMessage());
        // Narrower than the address: a key registered here would never be offered at all.
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("other.nordtal.eu", "https://steward.dev.nordtal.eu"));
    }

    @Test
    @DisplayName("localhost is a relying party, and only when the address is localhost too")
    void theLoopbackIsTheOneNamedException() {
        // season-2-ops/148. A Vite dev server is http://localhost:5173 and nothing else, WebAuthn
        // allows exactly one origin, so the relying party id there is `localhost` or there is no
        // sign-in to develop against. The rule above refused it for a reason that does not apply:
        // localhost is not a public suffix, every browser takes it, and it is a secure context.
        Configs.requireRelyingParty("localhost", "http://localhost:5173");
        Configs.requireRelyingParty("localhost", "https://localhost");
        Configs.requireRelyingParty("LocalHost", "http://LOCALHOST:8080");

        // AND IT WIDENS NOTHING, which is the half worth testing. The exception is one value
        // against one host, not "single labels are fine now".
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("eu", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("localhost", "https://steward.dev.nordtal.eu"));
        // The other direction: an address on localhost does not accept some other single label.
        assertThrows(
                IllegalArgumentException.class, () -> Configs.requireRelyingParty("intranet", "http://localhost:5173"));
        // And a name that merely ENDS in localhost is a different host, the same near miss the
        // dot in the comparison above exists for.
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("localhost", "http://notlocalhost:5173"));
    }

    @Test
    @DisplayName("a relying party written as a URL is refused, because it is a domain")
    void aRelyingPartyIsNotAUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("https://nordtal.eu", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("nordtal.eu:443", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty(null, "https://steward.dev.nordtal.eu"));
    }
}
