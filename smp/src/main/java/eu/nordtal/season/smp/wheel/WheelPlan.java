package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * What the wheel shows of a player's face: waiting for the press, or turning in one variant.
 * Body b shows the prize at place b, place 0 under the pointer, and a turn stops it at (b + variant) mod 12.
 *
 * @param variant the turn variant, how many places past whole turns the wheel stops, or {@link #WAITING}
 * @param face the prizes on the wheel before this turn
 */
record WheelPlan(int variant, Face face) {

    static final int PLACES = 12;

    /** The variant of a wheel that waits for the press, whose prizes pop in. */
    static final int WAITING = -1;

    /** The variant string the pack plays the pop-in for. */
    static final String OPEN = "open";

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
                    && a.data().equals(b.data());
        }
    }

    WheelPlan {
        if (variant < WAITING || variant >= PLACES) {
            throw new IllegalArgumentException("the wheel has no variant " + variant);
        }
    }

    /** The face waiting for the press, its prizes popping in. */
    static WheelPlan waiting(final Face face) {
        return new WheelPlan(WAITING, face);
    }

    /** The face turning in {@code variant}. */
    static WheelPlan turning(final Face face, final int variant) {
        return new WheelPlan(variant, face);
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

    /** The disc's custom model data strings: the variant and the grain's parity. */
    List<String> disc() {
        return List.of(variantString(), String.valueOf(face.parity()));
    }

    /** The strings that draw nothing at a place, whose prize the other prizes' body draws. */
    static final String NONE = "-";

    /**
     * A prizes body's strings: each place's prize, or {@link #NONE}, and the variant.
     * The glinting body draws the prizes {@code foil} marks, so its glint falls on them alone; the plain one the rest.
     */
    List<String> prizes(final List<Boolean> foil, final boolean glinting) {
        final List<String> out = new ArrayList<>(PLACES + 1);
        for (int body = 0; body < PLACES; body++) {
            out.add(foil.get(body) == glinting ? key(face.places().get(body)) : NONE);
        }
        out.add(variantString());
        return out;
    }

    /** The overlay's strings: each body's count, the variant and {@code language}. */
    List<String> overlay(final String language) {
        final List<String> out = new ArrayList<>(PLACES + 2);
        face.places().forEach(prize -> out.add(String.valueOf(prize.amount())));
        out.add(variantString());
        out.add(language);
        return out;
    }

    private String variantString() {
        return variant == WAITING ? OPEN : String.valueOf(variant);
    }

    /** The key the pack's turn selects a prize by: its item id without {@code minecraft:} and its components. */
    static String key(final WheelPrizeSpec prize) {
        final int components = prize.item().indexOf('[');
        final String item = components < 0 ? prize.item() : prize.item().substring(0, components);
        return item.startsWith("minecraft:") ? item.substring("minecraft:".length()) : item;
    }
}
