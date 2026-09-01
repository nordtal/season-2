package eu.nordtal.s2.smp.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a hand-in actually takes, worked out without a server.
 *
 * <p>Two rules from docs/smp.md#objective-types, and both of them are refusals:
 *
 * <ul>
 *   <li><b>Nothing can be handed in that no objective wants.</b> A stack of dirt in the deposit
 *       screen comes straight back rather than disappearing politely.</li>
 *   <li><b>No more than the objective still needs.</b> Over-delivery is not credited, and the
 *       surplus is returned rather than absorbed - somebody who empties a double chest into a
 *       nearly-finished objective must get the remainder back.</li>
 * </ul>
 *
 * <p><b>It deliberately knows nothing about {@code ItemStack}.</b> Constructing one needs a running
 * server's item factory, and this is the one place in the design where a bug takes items off a
 * player and gives nothing back - so it is expressed as material names and counts, asserted in
 * plain unit tests, and the GUI does the stack-shuffling against the answer.
 */
public final class HandIn {

    /** One stack as it sits in the deposit screen: which slot, what material, how many. */
    public record Offered(int slot, String material, int amount) {
    }

    /** What to do with one slot: keep {@code taken} of it, leave the rest with the player. */
    public record Take(int slot, int taken, int returned) {
    }

    /**
     * The verdict on a whole deposit.
     *
     * @param accepted how much counts towards the objective
     * @param takes    one entry per slot that gives something up
     */
    public record Result(long accepted, List<Take> takes) {

        public Result {
            takes = List.copyOf(takes);
        }
    }

    private HandIn() {
    }

    /**
     * Sorts a deposit into what is taken and what stays.
     *
     * @param offered     everything in the deposit screen; empty slots simply are not listed
     * @param wanted      the material names the objective accepts, case-insensitively
     * @param stillNeeded how much the objective is still short of its target
     */
    public static Result sort(final List<Offered> offered, final Set<String> wanted,
                              final long stillNeeded) {
        final List<Take> takes = new ArrayList<>();
        long accepted = 0;

        for (final Offered stack : offered) {
            if (stack == null || stack.amount() <= 0) {
                continue;
            }
            if (!isWanted(stack.material(), wanted) || accepted >= stillNeeded) {
                continue;
            }

            final long room = stillNeeded - accepted;
            final int taken = (int) Math.min(stack.amount(), room);
            if (taken <= 0) {
                continue;
            }
            takes.add(new Take(stack.slot(), taken, stack.amount() - taken));
            accepted += taken;
        }
        return new Result(accepted, takes);
    }

    private static boolean isWanted(final String material, final Set<String> wanted) {
        if (material == null || wanted == null || wanted.isEmpty()) {
            return false;
        }
        final String name = material.trim().toUpperCase(Locale.ROOT);
        for (final String want : wanted) {
            if (want != null && want.trim().toUpperCase(Locale.ROOT).equals(name)) {
                return true;
            }
        }
        return false;
    }
}
