package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.SmpRequest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TunnelMinerWheelTest {

    @Test
    void wheelDialogGlintsForBothEnchantedPickaxeAndStoredEnchantmentBook() {
        assertTrue(WheelDialog.glints(null, true, false));
        assertTrue(WheelDialog.glints(null, false, true));
        assertFalse(WheelDialog.glints(null, false, false));
        assertTrue(WheelDialog.glints(true, false, false));
        assertFalse(WheelDialog.glints(false, true, true));
    }

    @Test
    void takeInAnswerPreservesEncodedItemDataVerbatim() {
        final String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});
        final SmpRequest.TakeInItem.Answer answer =
                new SmpRequest.TakeInItem.Answer("minecraft:enchanted_book", encoded);
        assertEquals("minecraft:enchanted_book", answer.item());
        assertEquals(encoded, answer.data());
    }
}
