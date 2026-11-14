package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WheelPlanTest {

    private static WheelPrizeSpec prize(final String item, final int amount) {
        return prize(item, amount, 1);
    }

    private static WheelPrizeSpec prize(final String item, final int amount, final int weight) {
        return new WheelPrizeSpec() {
            @Override
            public int weight() {
                return weight;
            }

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

    private final WheelPlan.Face face = new WheelPlan.Face(numbered("minecraft:dirt"), 1);

    @Test
    void theBodyThatStopsUnderThePointerWinsAndTheFaceTurnsWithIt() {
        for (int variant = 0; variant < WheelPlan.PLACES; variant++) {
            final WheelPlan plan = WheelPlan.turning(face, variant);
            assertEquals(plan.bodies().get(WheelPlan.winnerBody(variant)), plan.winner());
            assertEquals(plan.winner(), plan.after().places().get(0), "variant " + variant);
            assertEquals((1 + variant) % 2, plan.after().parity());
            assertEquals(Set.copyOf(face.places()), Set.copyOf(plan.after().places()), "the prizes stay");
        }
    }

    @Test
    void theWaitingWheelPlaysThePopInAndKeepsItsFace() {
        final WheelPlan plan = WheelPlan.waiting(face);
        assertEquals(List.of("open", "1"), plan.disc());
        assertEquals(
                "open",
                plan.prizes(Collections.nCopies(WheelPlan.PLACES, false), false).get(WheelPlan.PLACES));
        assertEquals(face, plan.after());
    }

    @Test
    void theStringsNameEachBodysPrizeTheVariantAndTheLanguage() {
        final WheelPlan plan = WheelPlan.turning(face, 3);
        assertEquals(List.of("3", "1"), plan.disc());
        final List<Boolean> foil = new ArrayList<>(Collections.nCopies(WheelPlan.PLACES, false));
        foil.set(0, true);
        final List<String> plain = plan.prizes(foil, false);
        final List<String> glinting = plan.prizes(foil, true);
        assertEquals(WheelPlan.PLACES + 1, plain.size());
        assertEquals(WheelPlan.NONE, plain.get(0));
        assertEquals("dirt", glinting.get(0));
        assertEquals("dirt", plain.get(1));
        assertEquals(WheelPlan.NONE, glinting.get(1));
        assertEquals("3", plain.get(WheelPlan.PLACES));
        final List<String> overlay = plan.overlay("de");
        assertEquals(WheelPlan.PLACES + 2, overlay.size());
        assertEquals("10", overlay.get(9));
        assertEquals("3", overlay.get(12));
        assertEquals("de", overlay.get(13));
    }

    @Test
    void aPrizeIsSelectedByItsItemIdWithoutNamespaceOrComponents() {
        assertEquals("diamond_sword", WheelPlan.key(prize("minecraft:diamond_sword[enchantments={sharpness:5}]", 1)));
        assertEquals("dirt", WheelPlan.key(prize("minecraft:dirt", 1)));
    }

    @Test
    void aNewFaceShowsEachPrizeOfThePoolBeforeAnyTwice() {
        final List<WheelPrizeSpec> pool = numbered("minecraft:stone").subList(0, 5);
        final WheelPlan.Face drawn = WheelPlan.Face.random(pool, new Random(7));
        assertEquals(WheelPlan.PLACES, drawn.places().size());
        assertEquals(Set.copyOf(pool), Set.copyOf(drawn.places().subList(0, 5)));
        assertTrue(drawn.drawnFrom(pool));
        assertFalse(drawn.drawnFrom(pool.subList(0, 4)), "a prize that left the pool draws a new face");
    }

    @Test
    void theDrawLandsOnlyOnPlacesAVariantReachesAndFollowsTheWeights() {
        final List<WheelPrizeSpec> places = new ArrayList<>(numbered("minecraft:stone"));
        places.set(WheelPlan.winnerBody(4), prize("minecraft:diamond", 1, 1000));
        final WheelPlan.Face weighted = new WheelPlan.Face(places, 0);
        final Random random = new Random(1);
        int diamonds = 0;
        for (int i = 0; i < 2000; i++) {
            final int variant = weighted.draw(places, List.of(0, 4, 8), random);
            assertTrue(List.of(0, 4, 8).contains(variant));
            if (variant == 4) {
                diamonds++;
            }
        }
        assertTrue(diamonds > 1900, "the weight of 1000 against 1 and 1 wins most draws: " + diamonds);
    }
}
