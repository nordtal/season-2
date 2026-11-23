package eu.nordtal.season.smp.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GraveRestoreTest {

    @Test
    void aGraveEmptiedWhileTheRestoreReadsIsNotDrawnAgain() {
        final GraveRow kept = row();
        final GraveRow emptied = row();
        final GraveRestore restore = new GraveRestore();

        restore.begin();
        restore.touched(emptied.id());

        assertEquals(List.of(kept), restore.end(List.of(kept, emptied)));
    }

    @Test
    void aGraveTouchedBetweenTwoRestoresIsDrawnByTheSecond() {
        final GraveRow grave = row();
        final GraveRestore restore = new GraveRestore();
        restore.begin();
        restore.end(List.of());

        restore.touched(grave.id());
        restore.begin();

        assertEquals(List.of(grave), restore.end(List.of(grave)));
    }

    private static GraveRow row() {
        return new GraveRow(
                UUID.randomUUID(), "owner", UUID.randomUUID(), "world", 1, 2, 3, new byte[] {1}, 0, Instant.EPOCH);
    }
}
