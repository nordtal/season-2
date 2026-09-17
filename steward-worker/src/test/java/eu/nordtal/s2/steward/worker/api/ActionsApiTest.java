package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.audit.AuditEntry;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActionsApi#recent(int)}: one sorted list out of two tables, which is the whole reason this
 * class exists rather than the frontend merging {@code /api/journal} with a second call of its own.
 */
class ActionsApiTest {

    private static UpdateRequest run(final long id, final Instant finished, final UpdateKind kind) {
        return new UpdateRequest(id, kind, UpdateStatus.DONE, UpdateSource.CONSOLE, null,
                finished.minusSeconds(30), finished.minusSeconds(30), finished.minusSeconds(20),
                finished, "{\"stage\":\"DONE\",\"services\":[],\"notes\":[]}");
    }

    private static AuditEntry audit(final Instant occurred, final String action) {
        return new AuditEntry(UUID.randomUUID(), occurred, action, null, null, null, "detail");
    }

    @Test
    void mergesBothTablesAndSortsNewestFirst() {
        final Instant t1 = Instant.parse("2026-09-16T10:00:00Z");
        final Instant t2 = Instant.parse("2026-09-16T11:00:00Z");
        final Instant t3 = Instant.parse("2026-09-16T12:00:00Z");

        final ActionsApi api = new ActionsApi(
                FakeDirectories.updates(run(1, t1, UpdateKind.UPDATE), run(2, t3, UpdateKind.BACKUP)),
                FakeDirectories.audit(audit(t2, "GRANT_ACCESS")));

        final List<ActionEntry> entries = api.recent(5);
        assertEquals(3, entries.size());
        assertEquals(t3, entries.get(0).occurred());
        assertEquals(t2, entries.get(1).occurred());
        assertEquals(t1, entries.get(2).occurred());
    }

    @Test
    void clampsToTheRequestedLimit() {
        final Instant base = Instant.parse("2026-09-16T12:00:00Z");
        final ActionsApi api = new ActionsApi(
                FakeDirectories.updates(
                        run(1, base.minusSeconds(300), UpdateKind.UPDATE),
                        run(2, base.minusSeconds(200), UpdateKind.UPDATE),
                        run(3, base.minusSeconds(100), UpdateKind.UPDATE),
                        run(4, base, UpdateKind.UPDATE)),
                FakeDirectories.audit());

        final List<ActionEntry> entries = api.recent(2);
        assertEquals(2, entries.size());
        assertEquals(base, entries.get(0).occurred());
        assertEquals(base.minusSeconds(100), entries.get(1).occurred());
    }

    @Test
    void aLimitBelowOneIsClampedRatherThanReturningNothing() {
        final ActionsApi api = new ActionsApi(
                FakeDirectories.updates(run(1, Instant.parse("2026-09-16T12:00:00Z"), UpdateKind.UPDATE)),
                FakeDirectories.audit());

        assertEquals(1, api.recent(0).size());
    }

    @Test
    void heldKeyNeverAppearsHoweverRecentItIs() {
        final Instant now = Instant.parse("2026-09-16T12:00:00Z");
        final ActionsApi api = new ActionsApi(
                FakeDirectories.updates(),
                FakeDirectories.audit(
                        audit(now, "HELD_KEY"),
                        audit(now.minusSeconds(60), "GRANT_ACCESS")));

        final List<ActionEntry> entries = api.recent(5);
        assertEquals(1, entries.size());
        assertEquals("GRANT_ACCESS", entries.get(0).kind());
    }

    @Test
    void aRetiredApplyRunIsExcluded() {
        final ActionsApi api = new ActionsApi(
                FakeDirectories.updates(run(1, Instant.parse("2026-09-16T12:00:00Z"), UpdateKind.APPLY)),
                FakeDirectories.audit());

        assertTrue(api.recent(5).isEmpty());
    }
}
