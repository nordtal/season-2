package eu.nordtal.season.steward.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests {@code public-url}, which leaves the process as the Discord redirect URI. Discord answers a wrong value with {@code invalid_request}. */
class PublicUrlTest {

    @Test
    void aSchemeIsNotAUrl() {
        // A prefix check accepts both, and Discord refuses them at the first sign-in.
        assertThrows(IllegalArgumentException.class, () -> StewardSettings.requirePublicUrl("https://"));
        assertThrows(IllegalArgumentException.class, () -> StewardSettings.requirePublicUrl("http:///path"));
    }

    @Test
    void aQueryIsRefused() {
        assertThrows(
                IllegalArgumentException.class, () -> StewardSettings.requirePublicUrl("https://steward.example?x=1"));
        assertThrows(
                IllegalArgumentException.class, () -> StewardSettings.requirePublicUrl("https://steward.example#top"));
    }

    @Test
    void aTrailingSlashIsRefused() {
        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> StewardSettings.requirePublicUrl("https://steward.example/"));

        assertTrue(refused.getMessage().contains("two"), refused.getMessage());
    }

    @Test
    void anEmptyValueSaysWhatItIsFor() {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> StewardSettings.requirePublicUrl(""));

        assertTrue(refused.getMessage().contains("the address this interface answers on"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> StewardSettings.requirePublicUrl(null));
    }

    @Test
    void onlyHttpAndHttps() {
        assertThrows(IllegalArgumentException.class, () -> StewardSettings.requirePublicUrl("ftp://steward.example"));
    }

    @Test
    void theRealValuesPass() {
        assertDoesNotThrow(() -> StewardSettings.requirePublicUrl("https://steward.dev.nordtal.eu"));
        assertDoesNotThrow(() -> StewardSettings.requirePublicUrl("http://127.0.0.1:8080"));
        assertDoesNotThrow(() -> StewardSettings.requirePublicUrl("https://nordtal.eu/steward"));
    }

    @Test
    void aRelyingPartyMustBeTheAddressOrAParentOfIt() {
        // The production pair and its two correct shapes.
        StewardSettings.requireRelyingParty("nordtal.eu", "https://steward.dev.nordtal.eu");
        StewardSettings.requireRelyingParty("nordtal.eu", "https://nordtal.eu");
        StewardSettings.requireRelyingParty("steward.dev.nordtal.eu", "https://steward.dev.nordtal.eu");

        // A plain endsWith accepts `ordtal.eu` for `nordtal.eu`, so the comparison includes the dot.
        final IllegalArgumentException wrong = assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("ordtal.eu", "https://steward.dev.nordtal.eu"));
        assertTrue(
                wrong.getMessage().contains("ordtal.eu") && wrong.getMessage().contains("steward.dev.nordtal.eu"),
                "the message has to name both values, or nobody can see what does not match: " + wrong.getMessage());

        // A bare TLD is a suffix of the host too, so a second condition rejects public suffixes.
        final IllegalArgumentException tld = assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("eu", "https://steward.dev.nordtal.eu"));
        assertTrue(tld.getMessage().contains("registrable"), tld.getMessage());
        // A narrower ID: a key registered there is never offered.
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("other.nordtal.eu", "https://steward.dev.nordtal.eu"));
    }

    @Test
    void theLoopbackIsTheOneNamedException() {
        // localhost is no public suffix, every browser accepts it and it is a secure context.
        StewardSettings.requireRelyingParty("localhost", "http://localhost:5173");
        StewardSettings.requireRelyingParty("localhost", "https://localhost");
        StewardSettings.requireRelyingParty("LocalHost", "http://LOCALHOST:8080");

        // The exception widens nothing: it covers one value for one host, not every single label.
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("eu", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("localhost", "https://steward.dev.nordtal.eu"));
        // Reverse: an address on localhost does not accept another single label.
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("intranet", "http://localhost:5173"));
        // A name that only ends in localhost is another host, the near miss that the dot guards against.
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("localhost", "http://notlocalhost:5173"));
    }

    @Test
    void aRelyingPartyIsNotAUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("https://nordtal.eu", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("nordtal.eu:443", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty("", "https://steward.dev.nordtal.eu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StewardSettings.requireRelyingParty(null, "https://steward.dev.nordtal.eu"));
    }
}
