package eu.nordtal.season.smp.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaptureSessionsTest {

    @Test
    void onlyOneCaptureModeCanBeActivePerAdminAtATime() {
        final CaptureSessions sessions = new CaptureSessions();
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();

        assertTrue(sessions.tryBegin(alice, CaptureMode.AREA_EDITOR));
        assertTrue(sessions.isActive(alice));
        assertEquals(Optional.of(CaptureMode.AREA_EDITOR), sessions.activeMode(alice));

        assertFalse(sessions.tryBegin(alice, CaptureMode.SPAWN_EXCEPTIONS));
        assertFalse(sessions.tryBegin(alice, CaptureMode.ITEM_TAKE_IN));
        assertTrue(sessions.tryBegin(bob, CaptureMode.SPAWN_EXCEPTIONS));

        sessions.end(alice, CaptureMode.SPAWN_EXCEPTIONS);
        assertTrue(sessions.isActive(alice));

        sessions.end(alice, CaptureMode.AREA_EDITOR);
        assertFalse(sessions.isActive(alice));
        assertTrue(sessions.tryBegin(alice, CaptureMode.SPAWN_EXCEPTIONS));
    }
}
