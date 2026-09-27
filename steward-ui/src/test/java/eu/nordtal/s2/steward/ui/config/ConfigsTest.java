package eu.nordtal.s2.steward.ui.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code public-url}, the one value that leaves the process, as Discord's redirect URI.
 *
 * A wrong value comes back as Discord's {@code invalid_request}, so the check is stricter than a prefix.
 */
class ConfigsTest {

    @Test
    void aSchemeIsNotAUrl() {
        // A prefix check would say yes to both, so the first sign-in gets Discord's refusal instead.
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("https://"));
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("http:///path"));
    }

    @Test
    void aQueryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("https://steward.example?x=1"));
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("https://steward.example#top"));
    }

    @Test
    void aTrailingSlashIsRefused() {
        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> Configs.requirePublicUrl("https://steward.example/"));

        assertTrue(refused.getMessage().contains("two"), refused.getMessage());
    }

    @Test
    void anEmptyValueSaysWhatItIsFor() {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl(""));

        assertTrue(refused.getMessage().contains("the address this interface answers on"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl(null));
    }

    @Test
    void onlyHttpAndHttps() {
        assertThrows(IllegalArgumentException.class, () -> Configs.requirePublicUrl("ftp://steward.example"));
    }

    @Test
    void theRealValuesPass() {
        assertDoesNotThrow(() -> Configs.requirePublicUrl("https://steward.dev.nordtal.eu"));
        assertDoesNotThrow(() -> Configs.requirePublicUrl("http://127.0.0.1:8080"));
        assertDoesNotThrow(() -> Configs.requirePublicUrl("https://nordtal.eu/steward"));
    }

    @Test
    void aRelyingPartyMustBeTheAddressOrAParentOfIt() {
        // The production pair, and the two shapes of it that are correct.
        Configs.requireRelyingParty("nordtal.eu", "https://steward.dev.nordtal.eu");
        Configs.requireRelyingParty("nordtal.eu", "https://nordtal.eu");
        Configs.requireRelyingParty("steward.dev.nordtal.eu", "https://steward.dev.nordtal.eu");

        // A naive endsWith accepts `ordtal.eu` for `nordtal.eu`, so the dot is part of the comparison.
        final IllegalArgumentException wrong = assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("ordtal.eu", "https://steward.dev.nordtal.eu"));
        assertTrue(
                wrong.getMessage().contains("ordtal.eu") && wrong.getMessage().contains("steward.dev.nordtal.eu"),
                "the message has to name both values, or nobody can see what does not match: " + wrong.getMessage());

        // A bare TLD is a suffix of the host too, which is why a second condition rejects public suffixes.
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
    void theLoopbackIsTheOneNamedException() {
        // localhost is not a public suffix, every browser takes it, and it is a secure context.
        Configs.requireRelyingParty("localhost", "http://localhost:5173");
        Configs.requireRelyingParty("localhost", "https://localhost");
        Configs.requireRelyingParty("LocalHost", "http://LOCALHOST:8080");

        // It widens nothing: the exception is one value against one host, not every single label.
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("eu", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("localhost", "https://steward.dev.nordtal.eu"));
        // The other direction: an address on localhost does not accept some other single label.
        assertThrows(
                IllegalArgumentException.class, () -> Configs.requireRelyingParty("intranet", "http://localhost:5173"));
        // A name that merely ends in localhost is a different host, the same near miss the dot guards against.
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.requireRelyingParty("localhost", "http://notlocalhost:5173"));
    }

    @Test
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
