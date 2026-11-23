package eu.nordtal.season.database.inbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class SendPackTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String MAP = "fedcba9876543210fedcba9876543210fedcba98";

    @Test
    void aSwapCarriesTheMapPackOrNone() {
        assertDoesNotThrow(() -> new SmpRequest.SendPack("https://pack/a.zip", SHA1, null, null));
        assertDoesNotThrow(() -> new SmpRequest.SendPack("https://pack/a.zip", SHA1, "https://pack/m.zip", MAP));
    }

    @Test
    void aMapPackWithoutItsHashOrWithABadHashIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SmpRequest.SendPack("https://pack/a.zip", SHA1, "https://pack/m.zip", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SmpRequest.SendPack(
                        "https://pack/a.zip", SHA1, "https://pack/m.zip", MAP.toUpperCase(Locale.ROOT)));
    }
}
