package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import eu.nordtal.season.smp.config.WheelSpec;
import eu.nordtal.season.spec.Specs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
        final List<Boolean> plain = Collections.nCopies(WheelPlan.PLACES, false);
        final List<String> base = plan.base(plain);
        assertEquals("open", base.get(2 * WheelPlan.PLACES));
        assertEquals("1", base.get(2 * WheelPlan.PLACES + 1));
        assertEquals("open", plan.foil(plain).get(2 * WheelPlan.PLACES));
        assertEquals(face, plan.after());
    }

    @Test
    void theStringsSplitPlainAndGlintingPrizesWithTheirCounts() {
        final WheelPlan plan = WheelPlan.turning(face, 3);
        final List<Boolean> foil = new ArrayList<>(Collections.nCopies(WheelPlan.PLACES, false));
        foil.set(0, true);
        foil.set(9, true);
        final List<String> base = plan.base(foil);
        final List<String> glinting = plan.foil(foil);
        assertEquals(2 * WheelPlan.PLACES + 2, base.size());
        assertEquals(2 * WheelPlan.PLACES + 1, glinting.size());
        assertEquals(WheelPlan.NONE, base.get(0));
        assertEquals(WheelPlan.NO_COUNT, base.get(WheelPlan.PLACES));
        assertEquals("dirt", glinting.get(0));
        assertEquals("1", glinting.get(WheelPlan.PLACES));
        assertEquals("dirt", base.get(1));
        assertEquals("2", base.get(WheelPlan.PLACES + 1));
        assertEquals(WheelPlan.NONE, glinting.get(1));
        assertEquals(WheelPlan.NO_COUNT, glinting.get(WheelPlan.PLACES + 1));
        assertEquals(WheelPlan.NONE, base.get(9));
        assertEquals(WheelPlan.NO_COUNT, base.get(WheelPlan.PLACES + 9));
        assertEquals("dirt", glinting.get(9));
        assertEquals("10", glinting.get(WheelPlan.PLACES + 9));
        assertEquals("3", base.get(2 * WheelPlan.PLACES));
        assertEquals("1", base.get(2 * WheelPlan.PLACES + 1));
        assertEquals("3", glinting.get(2 * WheelPlan.PLACES));
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

    @Test
    void theBuiltInWheelPrizesOpenAndTurnWithoutStoredSettings() {
        final List<WheelPrizeSpec> pool = Specs.createDefault(WheelSpec.class).wheelPrizes();
        final WheelFaces faces = new WheelFaces(new Random(7));
        final UUID player = UUID.randomUUID();
        final WheelPlan.Face first = faces.next(player, pool).face();
        final WheelPlan.Face second = faces.next(player, pool).face();
        assertEquals(first, second);
        final int variant = first.draw(pool, List.of(0, 4, 8), new Random(1));
        assertTrue(List.of(0, 4, 8).contains(variant));
    }

    @Test
    void reboundEndingDrawsAtTwentyFivePercentAndKeepsWinnerAndAfterUnchanged() {
        final Random random = new Random(42);
        final int trials = 40_000;
        int rebounds = 0;
        for (int i = 0; i < trials; i++) {
            if (WheelPlan.drawRebound(random)) {
                rebounds++;
            }
        }
        final double rate = rebounds / (double) trials;
        assertTrue(rate > 0.24 && rate < 0.26, "rebound rate was " + rate);

        final List<Boolean> plain = Collections.nCopies(WheelPlan.PLACES, false);
        for (int variant = 0; variant < WheelPlan.PLACES; variant++) {
            final WheelPlan direct = WheelPlan.turning(face, variant, false);
            final WheelPlan rebound = WheelPlan.turning(face, variant, true);
            assertEquals(direct.winner(), rebound.winner(), "variant " + variant);
            assertEquals(direct.after(), rebound.after(), "variant " + variant);
            assertEquals(String.valueOf(variant), direct.base(plain).get(2 * WheelPlan.PLACES));
            assertEquals(variant + "r", rebound.base(plain).get(2 * WheelPlan.PLACES));
            assertEquals(variant + "r", rebound.foil(plain).get(2 * WheelPlan.PLACES));
        }
    }

    @Test
    void reboundChoiceIsIndependentOfWeightedPrizeSelection() {
        final List<WheelPrizeSpec> places = new ArrayList<>(numbered("minecraft:stone"));
        places.set(WheelPlan.winnerBody(2), prize("minecraft:diamond", 1, 9));
        places.set(WheelPlan.winnerBody(6), prize("minecraft:emerald", 1, 1));
        final WheelPlan.Face weighted = new WheelPlan.Face(places, 0);
        final Random random = new Random(123);
        int diamondTotal = 0;
        int diamondRebound = 0;
        int emeraldTotal = 0;
        int emeraldRebound = 0;
        final int trials = 40_000;
        for (int i = 0; i < trials; i++) {
            final int variant = weighted.draw(places, List.of(2, 6), random);
            final boolean rebound = WheelPlan.drawRebound(random);
            if (variant == 2) {
                diamondTotal++;
                if (rebound) {
                    diamondRebound++;
                }
            } else {
                emeraldTotal++;
                if (rebound) {
                    emeraldRebound++;
                }
            }
        }
        final double diamondShare = diamondTotal / (double) trials;
        final double diamondReboundRate = diamondRebound / (double) diamondTotal;
        final double emeraldReboundRate = emeraldRebound / (double) emeraldTotal;
        assertTrue(diamondShare > 0.89 && diamondShare < 0.91, "diamond share was " + diamondShare);
        assertTrue(diamondReboundRate > 0.23 && diamondReboundRate < 0.27, "diamond rebound was " + diamondReboundRate);
        assertTrue(emeraldReboundRate > 0.22 && emeraldReboundRate < 0.28, "emerald rebound was " + emeraldReboundRate);
    }
}
