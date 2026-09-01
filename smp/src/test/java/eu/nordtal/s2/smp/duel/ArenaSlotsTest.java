package eu.nordtal.s2.smp.duel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a duel arena is built, and - the part that matters - that two are never built in the same
 * place.
 *
 * <p>Arenas are placed as real blocks and taken away again, so two duels sharing a slot would mean
 * four people in one box, and the second one to finish would tear down the first one's floor.
 */
class ArenaSlotsTest {

    @Test
    void slotsAreHandedOutLowestFirst() {
        final ArenaSlots slots = new ArenaSlots(3, 200, 16);

        assertEquals(0, slots.claim().orElseThrow());
        assertEquals(1, slots.claim().orElseThrow());
        assertEquals(2, slots.claim().orElseThrow());
    }

    @Test
    void aFullGridHandsOutNothingAndTheNextDuelQueues() {
        final ArenaSlots slots = new ArenaSlots(2, 200, 16);
        slots.claim();
        slots.claim();

        assertTrue(slots.isFull());
        assertTrue(slots.claim().isEmpty(), "beyond the limit is a queue, not a third arena");
    }

    @Test
    void aReleasedSlotIsReusedBeforeAHigherOne() {
        final ArenaSlots slots = new ArenaSlots(3, 200, 16);
        slots.claim();
        final int second = slots.claim().orElseThrow();
        slots.release(second);

        assertEquals(second, slots.claim().orElseThrow(),
                "a busy evening must not push arenas into the skyline while a low one stands empty");
    }

    @Test
    void everySlotHasItsOwnHeightAndTheyDoNotOverlap() {
        final ArenaSlots slots = new ArenaSlots(4, 200, 16);

        assertEquals(200, slots.yOf(0));
        assertEquals(216, slots.yOf(1));
        assertEquals(232, slots.yOf(2));
        assertEquals(248, slots.yOf(3));
    }

    @Test
    void aClaimedSlotIsNeverHandedOutTwice() {
        final ArenaSlots slots = new ArenaSlots(3, 200, 16);
        final int first = slots.claim().orElseThrow();
        final int second = slots.claim().orElseThrow();
        final int third = slots.claim().orElseThrow();

        assertEquals(3, java.util.Set.of(first, second, third).size());
        assertEquals(3, slots.inUse());
    }

    @Test
    void nonsensicalConfigurationIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ArenaSlots(0, 200, 16));
        assertThrows(IllegalArgumentException.class, () -> new ArenaSlots(3, 200, 0));
    }

    @Test
    void releasingSomethingNeverClaimedChangesNothing() {
        final ArenaSlots slots = new ArenaSlots(2, 200, 16);
        slots.release(1);

        assertEquals(0, slots.inUse());
        assertFalse(slots.isFull());
    }
}
