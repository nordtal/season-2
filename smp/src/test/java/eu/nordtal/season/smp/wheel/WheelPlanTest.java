package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WheelPlanTest {

    private static WheelPrizeSpec prize(final String item, final int amount) {
        return new WheelPrizeSpec() {
            @Override
            public String item() {
                return item;
            }

            @Override
            public int amount() {
                return amount;
            }
        };
    }

    private static List<WheelPrizeSpec> numbered(final String item) {
        final List<WheelPrizeSpec> out = new ArrayList<>();
        for (int i = 0; i < WheelPlan.PLACES; i++) {
            out.add(prize(item, i + 1));
        }
        return out;
    }

    private final WheelPlan.Face before = new WheelPlan.Face(numbered("minecraft:dirt"), 1);
    private final WheelPrizeSpec winner = prize("minecraft:diamond", 16);

    @Test
    void theWinnerStopsUnderThePointerInEveryVariant() {
        for (int variant = 0; variant < WheelPlan.PLACES; variant++) {
            final WheelPlan plan =
                    WheelPlan.of(before, winner, numbered("minecraft:stone").subList(0, 11), variant);
            assertEquals(winner, plan.bodies().get(WheelPlan.winnerBody(variant)));
            assertEquals(winner, plan.after().places().get(0), "variant " + variant);
            assertEquals((1 + variant) % 2, plan.after().parity());
        }
    }

    @Test
    void theStringsNameEachBodysPrizeAndWhatItsPlaceShowedBefore() {
        final WheelPlan plan =
                WheelPlan.of(before, winner, numbered("minecraft:stone").subList(0, 11), 3);
        assertEquals(List.of("3", "1"), plan.disc());
        assertEquals(List.of("stone", "3", "dirt"), plan.prize(0));
        assertEquals(List.of("diamond", "3", "dirt"), plan.prize(9));
        final List<String> overlay = plan.overlay("de");
        assertEquals(2 * WheelPlan.PLACES + 2, overlay.size());
        assertEquals("16", overlay.get(9));
        assertEquals("3", overlay.get(12));
        assertEquals("1", overlay.get(13));
        assertEquals("de", overlay.get(25));
    }
}
