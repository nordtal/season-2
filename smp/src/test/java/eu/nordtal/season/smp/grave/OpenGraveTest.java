package eu.nordtal.season.smp.grave;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenGraveTest {

    @Test
    void anEmptiedGraveReopensWithoutItemsBeforeTheDatabaseAnswers() {
        final OpenGrave grave = graveWith(new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, grave.contentsForWindow().orElseThrow());

        grave.settle(Optional.empty());

        assertTrue(grave.contentsForWindow().isEmpty());
    }

    @Test
    void aPartlyLootedGraveReopensWithExactlyTheRemainder() {
        final OpenGrave grave = graveWith(new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, grave.contentsForWindow().orElseThrow());

        grave.settle(Optional.of(new byte[] {2}));

        assertArrayEquals(new byte[] {2}, grave.contentsForWindow().orElseThrow());
    }

    @Test
    void anExpiredGraveLetsNoItemLeaveItsOpenWindow() {
        final OpenGrave grave = graveWith(new byte[] {1, 2});
        assertTrue(GravePanel.leavesFree(grave.holdsLoot(), 0, 1));

        grave.expire();

        assertFalse(GravePanel.leavesFree(grave.holdsLoot(), 0, 1), "a content slot of the open window");
        assertFalse(GravePanel.leavesFree(grave.holdsLoot(), 20, 1), "a slot of the viewer's own inventory");
        assertTrue(grave.contentsForWindow().isEmpty());
    }

    @Test
    void aGraveExpiresOnceItWasMadeBeforeTheCutoff() {
        final OpenGrave grave = graveWith(new byte[] {1});

        assertFalse(grave.expiresBy(Instant.EPOCH));
        assertTrue(grave.expiresBy(Instant.EPOCH.plusMillis(1)));
    }

    private static OpenGrave graveWith(final byte[] contents) {
        return new OpenGrave(new GraveRow(
                UUID.randomUUID(), "owner", UUID.randomUUID(), "world", 1, 2, 3, contents, 7, Instant.EPOCH));
    }
}
