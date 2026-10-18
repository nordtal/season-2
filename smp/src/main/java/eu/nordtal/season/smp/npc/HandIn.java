package eu.nordtal.season.smp.npc;

import eu.nordtal.season.papercommon.game.GameKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Decides which offered stacks a hand-in takes. It works on material names and counts and needs no server. */
public final class HandIn {

    public record Offered(int slot, String material, int amount) {}

    public record Take(int slot, int taken, int returned) {}

    /**
     * @param accepted the amount that counts towards the objective
     * @param takes    one entry per slot that gives up items
     */
    public record Result(long accepted, List<Take> takes) {

        public Result {
            takes = List.copyOf(takes);
        }
    }

    private HandIn() {}

    /**
     * Takes only wanted items, and never more than {@code stillNeeded} in total.
     *
     * @param offered     the stacks in the deposit screen, without empty slots
     * @param wanted      the items the objective accepts, as keys or Bukkit names
     * @param stillNeeded the amount the objective lacks to reach its target
     */
    public static Result sort(final List<Offered> offered, final Set<String> wanted, final long stillNeeded) {
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
        final String key = GameKeys.key(material);
        for (final String want : wanted) {
            if (want != null && GameKeys.key(want).equals(key)) {
                return true;
            }
        }
        return false;
    }
}
