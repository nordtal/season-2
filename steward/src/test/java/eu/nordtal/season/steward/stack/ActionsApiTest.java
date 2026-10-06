package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link ActionsApi#recent(int)} merges two tables into one sorted list. */
class ActionsApiTest {

    private static UpdateRequest run(final long id, final Instant finished, final UpdateKind kind) {
        return new UpdateRequest(
                id,
                kind,
                UpdateStatus.DONE,
                Actor.HOST,
                finished.minusSeconds(30),
                finished.minusSeconds(30),
                null,
                List.of(),
                finished.minusSeconds(20),
                finished,
                "{\"stage\":\"DONE\",\"services\":[],\"notes\":[]}");
    }

    private static AuditEntry audit(final Instant occurred, final String action) {
        return new AuditEntry(
                UUID.randomUUID(), occurred, action, Actor.STEWARD, null, null, MessageRef.of("journal.link"));
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
                FakeDirectories.audit(audit(now, "HELD_KEY"), audit(now.minusSeconds(60), "GRANT_ACCESS")));

        final List<ActionEntry> entries = api.recent(5);
        assertEquals(1, entries.size());
        assertEquals("GRANT_ACCESS", entries.get(0).kind());
    }
}
