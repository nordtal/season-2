package eu.nordtal.season.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.pack.PackId;
import org.junit.jupiter.api.Test;

/** The proxy offers a pack under the id smp sends it under in a live swap, so a client holds it once. */
class PackOfferIdTest {

    @Test
    void theProxyAndSmpGiveAPackTheSameId() {
        final String sha1 = "b00eeb29fc8f57cdf7f8bbfdd00c2fdc4d6edcc0";
        assertEquals(PackId.of(sha1), PackOffer.idOf(sha1));
    }
}
