package eu.nordtal.s2.updater.arcane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one wrong value for {@code arcane.base-url} that looks right everywhere else.
 *
 * <p><b>Finding 40, 2026-09-03.</b> The first restart of the first deployment failed forty
 * milliseconds after the POST, against {@code http://localhost:3553}. Arcane was up and answering
 * that address in a browser the whole time - but the updater is a container, and {@code localhost}
 * inside it is that container. Nothing in the log said so; the row read "Could not reach Arcane",
 * which is true and useless, and the players were told the restart had been called off.
 *
 * <p>This is asserted as a string function and not through a request on purpose: the value is only
 * wrong <em>because of where the process runs</em>, so there is no environment in which a
 * connection attempt would tell you what these cases tell you.
 */
class ArcaneLoopbackTest {

    @Test
    @DisplayName("the address that actually cost the deployment is recognised")
    void theRealOne() {
        assertTrue(Arcane.loopback("http://localhost:3553"));
    }

    @Test
    @DisplayName("every spelling of 'this machine' counts")
    void everySpelling() {
        assertTrue(Arcane.loopback("http://localhost"), "no port");
        assertTrue(Arcane.loopback("https://localhost:8443"), "https");
        assertTrue(Arcane.loopback("http://127.0.0.1:3552"), "the usual address");
        assertTrue(Arcane.loopback("http://127.1.2.3:3552"), "the whole 127/8 block is loopback");
        assertTrue(Arcane.loopback("http://[::1]:3552"), "IPv6");
        assertTrue(Arcane.loopback("http://arcane.localhost:3552"), "the .localhost TLD");
        assertTrue(Arcane.loopback("  http://localhost:3553  "), "pasted with whitespace");
    }

    @Test
    @DisplayName("the fixes are not flagged")
    void theWaysOut() {
        assertFalse(Arcane.loopback("http://host.docker.internal:3553"),
                "the mapping compose.yml adds for this service");
        assertFalse(Arcane.loopback("http://arcane:3552"), "a container name on a shared network");
        assertFalse(Arcane.loopback("https://arcane.nordtal.eu"), "a real host");
        assertFalse(Arcane.loopback("http://192.168.1.10:3552"), "the host on the LAN");
    }

    @Test
    @DisplayName("nothing configured is not a loopback, and neither is nonsense")
    void nothingToWarnAbout() {
        // An empty base-url is a supported state - the restart button says so everywhere - and a
        // value that is not a URL at all is somebody else's error message, not this one's.
        assertFalse(Arcane.loopback(""));
        assertFalse(Arcane.loopback("   "));
        assertFalse(Arcane.loopback(null));
        assertFalse(Arcane.loopback("not a url at all"));
    }
}
