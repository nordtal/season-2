package eu.nordtal.season.database.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** A pack's id follows its SHA-1 alone. */
class PackIdTest {

    private static final String SHA1 = "b00eeb29fc8f57cdf7f8bbfdd00c2fdc4d6edcc0";

    @Test
    void theCaseOfTheHashDoesNotChangeTheId() {
        assertEquals(PackId.of(SHA1), PackId.of(SHA1.toUpperCase(Locale.ROOT)));
    }

    @Test
    void anotherPackHasAnotherId() {
        assertNotEquals(PackId.of(SHA1), PackId.of("0000000000000000000000000000000000000000"));
    }
}
