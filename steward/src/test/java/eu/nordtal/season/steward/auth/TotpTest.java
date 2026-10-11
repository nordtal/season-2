package eu.nordtal.season.steward.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The authenticator app's codes against the RFCs' own test vectors, and the sealed form its secret is stored in. */
class TotpTest {

    /** RFC 4226 Appendix D and RFC 6238 Appendix B both use this ASCII secret for SHA-1. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void hotpMatchesRfc4226AppendixD() {
        final List<String> expected = List.of(
                "755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489");
        for (int counter = 0; counter < expected.size(); counter++) {
            assertEquals(expected.get(counter), Totp.code(RFC_SECRET, counter, 6), "counter " + counter);
        }
    }

    @Test
    void totpMatchesRfc6238AppendixB() {
        assertEquals("94287082", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59L)), 8));
        assertEquals("07081804", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1_111_111_109L)), 8));
        assertEquals("14050471", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1_111_111_111L)), 8));
        assertEquals("89005924", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1_234_567_890L)), 8));
        assertEquals("69279037", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(2_000_000_000L)), 8));
        assertEquals("65353130", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(20_000_000_000L)), 8));
    }

    @Test
    void aCodeCountsWithinOneStepEitherSideAndNoFurther() {
        final long now = 1_000_000L;
        assertEquals(OptionalLong.of(now), Totp.matching(RFC_SECRET, Totp.code(RFC_SECRET, now, 6), now));
        assertEquals(OptionalLong.of(now - 1), Totp.matching(RFC_SECRET, Totp.code(RFC_SECRET, now - 1, 6), now));
        assertEquals(OptionalLong.of(now + 1), Totp.matching(RFC_SECRET, Totp.code(RFC_SECRET, now + 1, 6), now));
        assertTrue(Totp.matching(RFC_SECRET, Totp.code(RFC_SECRET, now - 2, 6), now)
                .isEmpty());
        assertTrue(Totp.matching(RFC_SECRET, Totp.code(RFC_SECRET, now + 2, 6), now)
                .isEmpty());
    }

    @Test
    void whatIsTypedIsReadLeniently() {
        final long now = 1_000_000L;
        final String code = Totp.code(RFC_SECRET, now, 6);
        assertEquals(
                OptionalLong.of(now),
                Totp.matching(RFC_SECRET, " " + code.substring(0, 3) + " " + code.substring(3) + " ", now));
        assertTrue(Totp.matching(RFC_SECRET, code.substring(0, 5), now).isEmpty(), "five digits");
        assertTrue(Totp.matching(RFC_SECRET, "abcdef", now).isEmpty(), "letters");
    }

    @Test
    void aSecretIsTwentyRandomBytes() {
        final byte[] one = Totp.newSecret();
        assertEquals(20, one.length, "RFC 4226 recommends 160 bits");
        assertTrue(!java.util.Arrays.equals(one, Totp.newSecret()), "two secrets were the same");
    }

    @Test
    void base32IsRfc4648WithoutPadding() {
        assertEquals("", Totp.base32(bytes("")));
        assertEquals("MY", Totp.base32(bytes("f")));
        assertEquals("MZXQ", Totp.base32(bytes("fo")));
        assertEquals("MZXW6", Totp.base32(bytes("foo")));
        assertEquals("MZXW6YQ", Totp.base32(bytes("foob")));
        assertEquals("MZXW6YTB", Totp.base32(bytes("fooba")));
        assertEquals("MZXW6YTBOI", Totp.base32(bytes("foobar")));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(RFC_SECRET));
    }

    @Test
    void theUriIsTheKeyUriFormat() {
        assertEquals(
                "otpauth://totp/Nordtal%20Steward:Ally%20%3A%20B?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                        + "&issuer=Nordtal%20Steward&algorithm=SHA1&digits=6&period=30",
                Totp.uri("Nordtal Steward", "Ally : B", RFC_SECRET));
    }

    @Test
    void aSealedSecretOpensOnlyWithItsKeyAndForItsAccount() {
        final SecretBox box = SecretBox.of("00".repeat(32)).orElseThrow();
        final byte[] sealed = box.seal(RFC_SECRET, bytes("1"));

        assertArrayEquals(RFC_SECRET, box.open(sealed, bytes("1")));
        assertTrue(!new String(sealed, StandardCharsets.ISO_8859_1).contains("1234567890"), "stored in the clear");
        assertThrows(IllegalStateException.class, () -> box.open(sealed, bytes("2")), "another account's row");
        assertThrows(
                IllegalStateException.class,
                () -> SecretBox.of("11".repeat(32)).orElseThrow().open(sealed, bytes("1")),
                "another key");
        assertTrue(SecretBox.of(" ").isEmpty(), "a blank key is no key");
        assertThrows(IllegalArgumentException.class, () -> SecretBox.of("abcd"), "a key of two bytes");
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
