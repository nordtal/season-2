package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One spin's arrangement of the 12 prizes on the wheel.
 * Body b starts at place b, place 0 under the pointer, and stops at (b + variant) mod 12.
 *
 * @param variant the turn variant: how many places past whole turns the wheel stops
 * @param bodies the prize of each body, body 0 first
 * @param before what the wheel showed before this spin
 */
record WheelPlan(int variant, List<WheelPrizeSpec> bodies, Face before) {

    static final int PLACES = 12;

    /**
     * What the wheel shows at rest: the prize at each place, place 0 first, and the parity of the disc's grain.
     * The grain stands at its frame 0 or 8, so the next turn starts from the grain the last one stopped on.
     */
    record Face(List<WheelPrizeSpec> places, int parity) {

        Face {
            places = List.copyOf(places);
        }

        /** A face for a player who has not seen the wheel since the server started: prizes drawn at random. */
        static Face random(final List<WheelPrizeSpec> pool, final Random random) {
            final List<WheelPrizeSpec> places = new ArrayList<>(PLACES);
            for (int place = 0; place < PLACES; place++) {
                places.add(pool.get(random.nextInt(pool.size())));
            }
            return new Face(places, 0);
        }
    }

    WheelPlan {
        bodies = List.copyOf(bodies);
        if (bodies.size() != PLACES || before.places().size() != PLACES) {
            throw new IllegalArgumentException("the wheel shows " + PLACES + " prizes");
        }
    }

    /** Puts {@code winner} into the body that stops under the pointer and {@code others}, 11 of them, into the rest. */
    static WheelPlan of(
            final Face before, final WheelPrizeSpec winner, final List<WheelPrizeSpec> others, final int variant) {
        final List<WheelPrizeSpec> bodies = new ArrayList<>(others);
        bodies.add(winnerBody(variant), winner);
        return new WheelPlan(variant, bodies, before);
    }

    /** The body whose prize stops under the pointer. */
    static int winnerBody(final int variant) {
        return Math.floorMod(-variant, PLACES);
    }

    /** What the wheel shows once this turn has stopped. */
    Face after() {
        final WheelPrizeSpec[] places = new WheelPrizeSpec[PLACES];
        for (int body = 0; body < PLACES; body++) {
            places[(body + variant) % PLACES] = bodies.get(body);
        }
        return new Face(List.of(places), (before.parity() + variant) % 2);
    }

    /** The disc's custom model data strings: the variant and the grain's parity. */
    List<String> disc() {
        return List.of(String.valueOf(variant), String.valueOf(before.parity()));
    }

    /** Prize body {@code body}'s strings: its prize, the variant, and the prize its place showed before. */
    List<String> prize(final int body) {
        return List.of(
                key(bodies.get(body)),
                String.valueOf(variant),
                key(before.places().get(body)));
    }

    /** The overlay's strings: each body's count, the variant, each place's count before, and {@code language}. */
    List<String> overlay(final String language) {
        final List<String> out = new ArrayList<>(2 * PLACES + 2);
        bodies.forEach(prize -> out.add(String.valueOf(prize.amount())));
        out.add(String.valueOf(variant));
        before.places().forEach(prize -> out.add(String.valueOf(prize.amount())));
        out.add(language);
        return out;
    }

    /** The key the pack's turn selects a prize by: its item id without {@code minecraft:}. */
    static String key(final WheelPrizeSpec prize) {
        final String item = prize.item();
        return item.startsWith("minecraft:") ? item.substring("minecraft:".length()) : item;
    }
}
