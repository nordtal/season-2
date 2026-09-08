package eu.nordtal.s2.updater.arcane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second wrong value for {@code arcane.base-url}, and the reason nobody could see it was one.
 *
 * <p><b>2026-09-05.</b> The restart failed against {@code http://docker.host.internal:3553} and the
 * whole of what reached the admin channel was
 * {@code "Could not reach Arcane at ...: java.net.ConnectException"}. That string is what
 * {@code failure.toString()} produces for a name that does not resolve, because the JDK's
 * {@code HttpClient} wraps the {@code UnresolvedAddressException} in a {@code ConnectException} it
 * gives no message to. So a DNS mistake, a refused connection and a dropped route all print the
 * same eleven characters, and the one thing a reader needs - which of the three - is exactly what
 * is missing.
 *
 * <p>Asserted on strings and constructed exceptions, never through a request. That is the same
 * decision {@link ArcaneLoopbackTest} documents and for the same reason: these values are wrong
 * because of <em>where the process runs</em>, so no connection attempt from a laptop reproduces
 * them.
 */
class ArcaneDiagnosisTest {

    @Test
    @DisplayName("plain HTTP with an API key is named; HTTPS, no key and no URL are not")
    void aCredentialOnAnUnencryptedConnectionIsNamed() {
        // The local stack is the one place this is defensible - container to host over Docker's own
        // bridge - which is why it is a warning and not a refusal. Anywhere else the X-Api-Key
        // header is a redeploy credential in the clear (finding 114).
        assertTrue(Arcane.cleartextWithKey("http://host.docker.internal:3552", "k"));
        assertTrue(Arcane.cleartextWithKey("HTTP://arcane.example.com", "k"),
                "the scheme is case-insensitive, and an operator's paste is not");
        assertFalse(Arcane.cleartextWithKey("https://arcane.example.com", "k"));
        assertFalse(Arcane.cleartextWithKey("http://arcane.example.com", ""),
                "no key is nothing to leak; Arcane refuses the request itself");
        assertFalse(Arcane.cleartextWithKey("", "k"), "an unconfigured Arcane is a supported state");
    }

    @Test
    @DisplayName("the cause is printed, because the exception on its own says nothing")
    void theCauseIsWhereTheAnswerIs() {
        final ConnectException asThrown = new ConnectException();
        asThrown.initCause(new UnresolvedAddressException());

        // The half that fails without this: toString() of the outer exception alone.
        assertEquals("java.net.ConnectException", asThrown.toString());
        assertEquals("java.net.ConnectException <- java.nio.channels.UnresolvedAddressException",
                Arcane.causeChain(asThrown));
    }

    @Test
    @DisplayName("a chain is followed, and a self-referencing cause does not hang the report")
    void theChainIsBounded() {
        final RuntimeException inner = new RuntimeException("inner");
        final RuntimeException middle = new RuntimeException("middle", inner);
        assertEquals("java.lang.RuntimeException: outer"
                        + " <- java.lang.RuntimeException: middle"
                        + " <- java.lang.RuntimeException: inner",
                Arcane.causeChain(new RuntimeException("outer", middle)));

        // A throwable whose cause is itself is legal and does happen; the report must not loop.
        final RuntimeException loop = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertTrue(Arcane.causeChain(loop).length() < 1000);
    }

    @Test
    @DisplayName("the value that actually failed is named, with the spelling that works")
    void theRealOne() {
        assertEquals("host.docker.internal",
                Arcane.transposedDockerHost("http://docker.host.internal:3553").orElseThrow());
    }

    @Test
    @DisplayName("any order of the three labels is the same mistake")
    void everyTransposition() {
        assertTrue(Arcane.transposedDockerHost("http://internal.docker.host").isPresent());
        assertTrue(Arcane.transposedDockerHost("http://docker.internal.host:3553").isPresent());
        assertTrue(Arcane.transposedDockerHost("HTTP://DOCKER.HOST.INTERNAL:3553").isPresent(),
                "typed in capitals, still the same host");
        assertTrue(Arcane.transposedDockerHost("  http://docker.host.internal:3553  ").isPresent(),
                "pasted with whitespace");
    }

    @Test
    @DisplayName("the correct host, and everything unrelated, is left alone")
    void nothingToSuggest() {
        assertFalse(Arcane.transposedDockerHost("http://host.docker.internal:3553").isPresent(),
                "the value that works must never be reported as a mistake");
        assertFalse(Arcane.transposedDockerHost("http://arcane:3552").isPresent());
        assertFalse(Arcane.transposedDockerHost("https://arcane.nordtal.eu").isPresent());
        assertFalse(Arcane.transposedDockerHost("http://host.docker.internal.example.com").isPresent(),
                "four labels is a real domain somebody owns, not a typo");
        assertFalse(Arcane.transposedDockerHost("").isPresent());
        assertFalse(Arcane.transposedDockerHost(null).isPresent());
        assertFalse(Arcane.transposedDockerHost("not a url at all").isPresent());
    }

    @Test
    @DisplayName("the sentence that reaches the admin channel carries both the cause and the fix")
    void theWholeSentence() {
        final ConnectException asThrown = new ConnectException();
        asThrown.initCause(new UnresolvedAddressException());
        final URI uri = URI.create("http://docker.host.internal:3553/api/environments/0/projects/x/redeploy");

        final String sentence = Arcane.unreachable(uri, asThrown, "http://docker.host.internal:3553");

        assertTrue(sentence.contains("UnresolvedAddressException"), sentence);
        assertTrue(sentence.contains("host.docker.internal"), sentence);
        assertTrue(sentence.contains("wrong order"), sentence);
    }

    @Test
    @DisplayName("a loopback still gets the older advice, and it is not given twice")
    void theTwoAdvicesDoNotStack() {
        final ConnectException asThrown = new ConnectException("Connection refused");
        final URI uri = URI.create("http://localhost:3553/api/x");

        final String sentence = Arcane.unreachable(uri, asThrown, "http://localhost:3553");

        assertTrue(sentence.contains("Connection refused"), sentence);
        assertTrue(sentence.contains("loopback address"), sentence);
        assertFalse(sentence.contains("wrong order"), "only one diagnosis fits a value: " + sentence);
    }

    @Test
    @DisplayName("a host that is simply unreachable gets no invented advice")
    void nothingIsMadeUp() {
        final String sentence = Arcane.unreachable(
                URI.create("https://arcane.nordtal.eu/api/x"),
                new ConnectException("Operation timed out"),
                "https://arcane.nordtal.eu");

        assertTrue(sentence.endsWith("java.net.ConnectException: Operation timed out"), sentence);
    }

    // ---------------------------------------------------------------- the same-host exception

    @Test
    @DisplayName("plain HTTP with a key is refused for a remote host and allowed for this one")
    void cleartextIsOnlyDefensibleOnThisMachine() {
        // The constructor has warned about this since finding 114, and a warning is what a log
        // holds and nobody reads. Every request carries the X-Api-Key header, so from 2026-09-08
        // the check sits in front of the request instead - and the exception has to stay explicit,
        // because the local stack genuinely does talk to the host over Docker's own bridge.
        assertTrue(Arcane.sameHost("http://host.docker.internal:3552"),
                "the documented value for the local stack: the request never leaves the machine");
        assertTrue(Arcane.sameHost("http://localhost:3552"),
                "broken for another reason entirely - see loopback() - but not a leaked credential");
        assertTrue(Arcane.sameHost("http://127.0.0.1:3552"));

        assertFalse(Arcane.sameHost("http://arcane.nordtal.eu"),
                "a real host over plain HTTP is a redeploy credential on the wire");
        assertFalse(Arcane.sameHost("http://10.0.0.4:3552"));
    }
}
