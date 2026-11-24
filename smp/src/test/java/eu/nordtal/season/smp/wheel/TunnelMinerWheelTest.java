package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.smp.TakenItems;
import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
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
    void wheelPlanRoutesGlintingTunnelMinerPrizesToTheFoilLayer() {
        final List<WheelPrizeSpec> prizes = IntStream.range(0, WheelPlan.PLACES)
                .mapToObj(i -> prize(i == 0 ? "minecraft:enchanted_book" : "minecraft:diamond_pickaxe"))
                .toList();
        final List<Boolean> foil = IntStream.range(0, WheelPlan.PLACES)
                .mapToObj(i -> i == 0 || i == 1)
                .toList();
        final WheelPlan plan = new WheelPlan(WheelPlan.WAITING, new WheelPlan.Face(prizes, 0));
        final List<String> foilSprites = plan.foil(foil);
        final List<String> plainSprites = plan.base(foil);
        assertEquals(2 * WheelPlan.PLACES + 1, foilSprites.size());
        assertEquals(2 * WheelPlan.PLACES + 2, plainSprites.size());
        assertEquals("enchanted_book", foilSprites.get(0));
        assertEquals("diamond_pickaxe", foilSprites.get(1));
        assertEquals(WheelPlan.NONE, plainSprites.get(0));
        assertEquals(WheelPlan.NONE, plainSprites.get(1));
    }

    @Test
    void takeInOutcomeAndHandoutNamingPreserveTunnelMinerItems() {
        final String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});
        final SmpRequest.TakeInItem.Answer answer =
                new SmpRequest.TakeInItem.Answer("minecraft:enchanted_book", encoded);
        final Outcome.Done done = (Outcome.Done) Outcome.done(answer);
        assertEquals(answer, done.answer());
        assertEquals("minecraft:enchanted_book", answer.item());
        assertEquals(encoded, answer.data());

        assertNull(TakenItems.one("minecraft:enchanted_book", "not-valid-base64!!!"));
        assertNull(TakenItems.one("", ""));
        assertEquals(
                GameContent.of("item.minecraft.enchanted_book"),
                ItemHandout.named(null, null, "item.minecraft.enchanted_book"));
        assertEquals(
                GameContent.of("item.minecraft.diamond_pickaxe"),
                ItemHandout.named(null, null, "item.minecraft.diamond_pickaxe"));
    }

    private static WheelPrizeSpec prize(final String item) {
        return new WheelPrizeSpec() {
            @Override
            public int weight() {
                return 1;
            }

            @Override
            public String item() {
                return item;
            }

            @Override
            public int amount() {
                return 1;
            }
        };
    }
}
