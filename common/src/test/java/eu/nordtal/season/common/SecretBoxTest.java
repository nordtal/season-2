package eu.nordtal.season.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void aSealedSecretOpensOnlyWithItsKeyAndForItsContext() {
        final SecretBox box = SecretBox.of("00".repeat(32)).orElseThrow();
        final byte[] sealed = box.seal(SECRET, bytes("1"));

        assertArrayEquals(SECRET, box.open(sealed, bytes("1")));
        assertTrue(!new String(sealed, StandardCharsets.ISO_8859_1).contains("1234567890"), "stored in the clear");
        assertThrows(IllegalStateException.class, () -> box.open(sealed, bytes("2")), "another context");
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
