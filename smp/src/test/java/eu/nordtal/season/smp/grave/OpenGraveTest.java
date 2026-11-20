package eu.nordtal.season.smp.grave;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    private static OpenGrave graveWith(final byte[] contents) {
        return new OpenGrave(new GraveRow(
                UUID.randomUUID(), "owner", UUID.randomUUID(), "world", 1, 2, 3, contents, 7, Instant.EPOCH));
    }
}
