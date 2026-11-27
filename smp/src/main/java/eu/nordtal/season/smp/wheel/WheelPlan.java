package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * What the wheel shows of a player's face: waiting for the press, or turning in one variant and ending.
 * Body b shows the prize at place b, place 0 under the pointer, and a turn stops it at (b + variant) mod 12.
 *
 * @param variant the turn variant, how many places past whole turns the wheel stops, or {@link #WAITING}
 * @param rebound whether the turn overshoots onto the far pin and rocks back instead of stopping directly
 * @param face the prizes on the wheel before this turn
 */
record WheelPlan(int variant, boolean rebound, Face face) {

    static final int PLACES = 12;

    /** One in four spins uses the overshoot-and-rock-back ending, independent of the prize draw. */
    static final int REBOUND_DENOMINATOR = 4;

    /** The variant of a wheel that waits for the press, whose prizes pop in. */
    static final int WAITING = -1;

    /** The variant string the pack plays the pop-in for. */
    static final String OPEN = "open";

    /** The string that draws nothing at a place whose prize the other body draws. */
    static final String NONE = "-";

    /** The count string that draws no stack count at a place. */
    static final String NO_COUNT = "1";

    /**
     * The prizes at each place, place 0 first, and the parity of the disc's grain.
     * The grain stands at its frame 0 or 8, so the next turn starts from the grain the last one stopped on.
     */
    record Face(List<WheelPrizeSpec> places, int parity) {

        Face {
            places = List.copyOf(places);
            if (places.size() != PLACES) {
                throw new IllegalArgumentException("the wheel shows " + PLACES + " prizes");
            }
        }

        /** A new face: the prizes of {@code pool} in random places, each before any shows twice. */
        static Face random(final List<WheelPrizeSpec> pool, final Random random) {
            if (pool.isEmpty()) {
                throw new IllegalArgumentException("the wheel has no prizes");
            }
            final List<WheelPrizeSpec> places = new ArrayList<>(PLACES);
            while (places.size() < PLACES) {
                final List<WheelPrizeSpec> round = new ArrayList<>(pool);
                Collections.shuffle(round, random);
                places.addAll(round.subList(0, Math.min(round.size(), PLACES - places.size())));
            }
            return new Face(places, 0);
        }

        /** Whether every prize on the face is still in {@code pool}, so the face may show again. */
        boolean drawnFrom(final List<WheelPrizeSpec> pool) {
            return places.stream().allMatch(place -> pool.stream().anyMatch(prize -> same(place, prize)));
        }

        /**
         * The variant that turns, drawn by the weight in {@code pool} of the prize it stops under the pointer.
         * Only the places one of {@code variants} reaches can win.
         */
        int draw(final List<WheelPrizeSpec> pool, final List<Integer> variants, final Random random) {
            final List<Integer> weights = new ArrayList<>(variants.size());
            variants.forEach(variant -> weights.add(weightOf(places.get(winnerBody(variant)), pool)));
            return variants.get(PrizeDraw.draw(weights, random));
        }

        private static int weightOf(final WheelPrizeSpec place, final List<WheelPrizeSpec> pool) {
            return pool.stream()
                    .filter(prize -> same(place, prize))
                    .mapToInt(WheelPrizeSpec::weight)
                    .findFirst()
                    .orElse(place.weight());
        }

        static boolean same(final WheelPrizeSpec a, final WheelPrizeSpec b) {
            return a.item().equals(b.item())
                    && a.amount() == b.amount()
                    && a.data().equals(b.data())
                    && a.structure().equals(b.structure());
        }
    }

    WheelPlan {
        if (variant < WAITING || variant >= PLACES) {
            throw new IllegalArgumentException("the wheel has no variant " + variant);
        }
        if (variant == WAITING && rebound) {
            throw new IllegalArgumentException("a waiting wheel has no rebound ending");
        }
    }

    /** Draws whether a spin uses the rebound ending, independently of prize selection, at a 25 percent chance. */
    static boolean drawRebound(final Random random) {
        return random.nextInt(REBOUND_DENOMINATOR) == 0;
    }

    /** The face waiting for the press, its prizes popping in. */
    static WheelPlan waiting(final Face face) {
        return new WheelPlan(WAITING, false, face);
    }

    /** The face turning in {@code variant} with a direct stop. */
    static WheelPlan turning(final Face face, final int variant) {
        return turning(face, variant, false);
    }

    /** The face turning in {@code variant} with either a direct stop or a rebound ending. */
    static WheelPlan turning(final Face face, final int variant, final boolean rebound) {
        return new WheelPlan(variant, rebound, face);
    }

    /** The body whose prize stops under the pointer in {@code variant}. */
    static int winnerBody(final int variant) {
        return Math.floorMod(-variant, PLACES);
    }

    List<WheelPrizeSpec> bodies() {
        return face.places();
    }

    /** The prize this turn stops under the pointer. */
    WheelPrizeSpec winner() {
        return face.places().get(winnerBody(variant));
    }

    /** What the wheel shows once this turn has stopped: the same prizes, turned. */
    Face after() {
        if (variant == WAITING) {
            return face;
        }
        final WheelPrizeSpec[] places = new WheelPrizeSpec[PLACES];
        for (int body = 0; body < PLACES; body++) {
            places[(body + variant) % PLACES] = face.places().get(body);
        }
        return new Face(List.of(places), (face.parity() + variant) % 2);
    }

    /**
     * The base body's strings: plain prize keys, plain prize counts, variant and grain parity.
     * Glinting places hold {@link #NONE} and {@link #NO_COUNT} so the foil body draws them instead.
     */
    List<String> base(final List<Boolean> foil) {
        final List<String> out = new ArrayList<>(2 * PLACES + 2);
        addPrizesAndCounts(out, foil, false);
        out.add(variantString());
        out.add(String.valueOf(face.parity()));
        return out;
    }

    /**
     * The foil body's strings: glinting prize keys, glinting prize counts and variant.
     * Plain places hold {@link #NONE} and {@link #NO_COUNT} so the base body draws them instead.
     */
    List<String> foil(final List<Boolean> foil) {
        final List<String> out = new ArrayList<>(2 * PLACES + 1);
        addPrizesAndCounts(out, foil, true);
        out.add(variantString());
        return out;
    }

    private void addPrizesAndCounts(final List<String> out, final List<Boolean> foil, final boolean glinting) {
        for (int body = 0; body < PLACES; body++) {
            out.add(foil.get(body) == glinting ? key(face.places().get(body)) : NONE);
        }
        for (int body = 0; body < PLACES; body++) {
            out.add(
                    foil.get(body) == glinting
                            ? String.valueOf(face.places().get(body).amount())
                            : NO_COUNT);
        }
    }

    private String variantString() {
        if (variant == WAITING) {
            return OPEN;
        }
        return rebound ? variant + "r" : String.valueOf(variant);
    }

    /** The key the pack's turn selects a prize by: its item id without {@code minecraft:} and its components. */
    static String key(final WheelPrizeSpec prize) {
        final int components = prize.item().indexOf('[');
        final String item = components < 0 ? prize.item() : prize.item().substring(0, components);
        return item.startsWith("minecraft:") ? item.substring("minecraft:".length()) : item;
    }
}
