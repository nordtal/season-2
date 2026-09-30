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
class UiSettingsTest {

    @Test
    void aSchemeIsNotAUrl() {
        // A prefix check would say yes to both, so the first sign-in gets Discord's refusal instead.
        assertThrows(IllegalArgumentException.class, () -> UiSettings.requirePublicUrl("https://"));
        assertThrows(IllegalArgumentException.class, () -> UiSettings.requirePublicUrl("http:///path"));
    }

    @Test
    void aQueryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> UiSettings.requirePublicUrl("https://steward.example?x=1"));
        assertThrows(IllegalArgumentException.class, () -> UiSettings.requirePublicUrl("https://steward.example#top"));
    }

    @Test
    void aTrailingSlashIsRefused() {
        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> UiSettings.requirePublicUrl("https://steward.example/"));

        assertTrue(refused.getMessage().contains("two"), refused.getMessage());
    }

    @Test
    void anEmptyValueSaysWhatItIsFor() {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> UiSettings.requirePublicUrl(""));

        assertTrue(refused.getMessage().contains("the address this interface answers on"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> UiSettings.requirePublicUrl(null));
    }

    @Test
    void onlyHttpAndHttps() {
        assertThrows(IllegalArgumentException.class, () -> UiSettings.requirePublicUrl("ftp://steward.example"));
    }

    @Test
    void theRealValuesPass() {
        assertDoesNotThrow(() -> UiSettings.requirePublicUrl("https://steward.dev.nordtal.eu"));
        assertDoesNotThrow(() -> UiSettings.requirePublicUrl("http://127.0.0.1:8080"));
        assertDoesNotThrow(() -> UiSettings.requirePublicUrl("https://nordtal.eu/steward"));
    }

    @Test
    void aRelyingPartyMustBeTheAddressOrAParentOfIt() {
        // The production pair, and the two shapes of it that are correct.
        UiSettings.requireRelyingParty("nordtal.eu", "https://steward.dev.nordtal.eu");
        UiSettings.requireRelyingParty("nordtal.eu", "https://nordtal.eu");
        UiSettings.requireRelyingParty("steward.dev.nordtal.eu", "https://steward.dev.nordtal.eu");

        // A naive endsWith accepts `ordtal.eu` for `nordtal.eu`, so the dot is part of the comparison.
        final IllegalArgumentException wrong = assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("ordtal.eu", "https://steward.dev.nordtal.eu"));
        assertTrue(
                wrong.getMessage().contains("ordtal.eu") && wrong.getMessage().contains("steward.dev.nordtal.eu"),
                "the message has to name both values, or nobody can see what does not match: " + wrong.getMessage());

        // A bare TLD is a suffix of the host too, which is why a second condition rejects public suffixes.
        final IllegalArgumentException tld = assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("eu", "https://steward.dev.nordtal.eu"));
        assertTrue(tld.getMessage().contains("registrable"), tld.getMessage());
        // Narrower than the address: a key registered here would never be offered at all.
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("other.nordtal.eu", "https://steward.dev.nordtal.eu"));
    }

    @Test
    void theLoopbackIsTheOneNamedException() {
        // localhost is not a public suffix, every browser takes it, and it is a secure context.
        UiSettings.requireRelyingParty("localhost", "http://localhost:5173");
        UiSettings.requireRelyingParty("localhost", "https://localhost");
        UiSettings.requireRelyingParty("LocalHost", "http://LOCALHOST:8080");

        // It widens nothing: the exception is one value against one host, not every single label.
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("eu", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("localhost", "https://steward.dev.nordtal.eu"));
        // The other direction: an address on localhost does not accept some other single label.
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("intranet", "http://localhost:5173"));
        // A name that merely ends in localhost is a different host, the same near miss the dot guards against.
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("localhost", "http://notlocalhost:5173"));
    }

    @Test
    void aRelyingPartyIsNotAUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("https://nordtal.eu", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("nordtal.eu:443", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty("", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UiSettings.requireRelyingParty(null, "https://steward.dev.nordtal.eu"));
    }
}
