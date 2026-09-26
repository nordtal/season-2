package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.audit.AuditEntry;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mapping from a raw row of either table to one {@link ActionEntry} - the extent computed from
 * an {@code UpdateReport}, and who gets credited with a run or a journal line.
 */
class ActionEntryTest {

    private static final Instant REQUESTED = Instant.parse("2026-09-16T12:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-09-16T12:05:00Z");

    private static UpdateRequest run(
            final UpdateKind kind, final UpdateStatus status, final String requestedBy, final String result) {
        return new UpdateRequest(
                1L,
                kind,
                status,
                UpdateSource.CONSOLE,
                requestedBy,
                REQUESTED,
                REQUESTED,
                REQUESTED,
                status.isFinished() ? FINISHED : null,
                result);
    }

    // ---------------------------------------------------------------- extent, from a real report

    @Test
    void countsHealthyAndSavedLinesAgainstEverythingTouched() {
        // One HEALTHY, one SAVED, one FAILED, one UNCHANGED (not touched) and one PLANNED (not
        // touched either) - so total is 3 and successful is 2.
        final String report = "{\"stage\":\"DONE\",\"services\":["
                + "{\"service\":\"a\",\"state\":\"HEALTHY\",\"changes\":[]},"
                + "{\"service\":\"b\",\"state\":\"SAVED\",\"changes\":[]},"
                + "{\"service\":\"c\",\"state\":\"FAILED\",\"changes\":[]},"
                + "{\"service\":\"d\",\"state\":\"UNCHANGED\",\"changes\":[]},"
                + "{\"service\":\"e\",\"state\":\"PLANNED\",\"changes\":[]}"
                + "],\"notes\":[]}";
        final ActionEntry entry =
                ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, "hm.till" + " (594510749410525200)", report));
        assertEquals("2/3 successful", entry.extent());
    }

    @Test
    void nothingTouchedReadsTheReportsOwnHeadline() {
        final String report = "{\"stage\":\"NOTHING_TO_DO\",\"services\":["
                + "{\"service\":\"a\",\"state\":\"UNCHANGED\",\"changes\":[]}],\"notes\":[]}";
        final ActionEntry entry = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, null, report));
        assertEquals("Everything is already current", entry.extent());
    }

    @Test
    void unparseableResultFallsBackToTheStatusWord() {
        // A worker older than 2026-09-07 wrote plain text, or the row has none yet.
        final ActionEntry done = ActionEntry.of(
                run(UpdateKind.UPDATE, UpdateStatus.DONE, null, "some plain text a very old worker wrote"));
        assertEquals("done", done.extent());

        final ActionEntry failed = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.FAILED, null, null));
        assertEquals("failed", failed.extent());
    }

    @Test
    void pendingAndRunningAreSaidRatherThanGuessedAt() {
        assertEquals(
                "pending",
                ActionEntry.of(new UpdateRequest(
                                1L,
                                UpdateKind.RESTART,
                                UpdateStatus.PENDING,
                                UpdateSource.CONSOLE,
                                null,
                                REQUESTED,
                                REQUESTED,
                                null,
                                null,
                                null))
                        .extent());
        assertEquals(
                "running",
                ActionEntry.of(new UpdateRequest(
                                1L,
                                UpdateKind.RESTART,
                                UpdateStatus.RUNNING,
                                UpdateSource.CONSOLE,
                                null,
                                REQUESTED,
                                REQUESTED,
                                REQUESTED,
                                null,
                                null))
                        .extent());
    }

    // ---------------------------------------------------------------- who gets credited, for a run

    @Test
    void aNullRequesterIsTheSystem() {
        final ActionEntry entry = ActionEntry.of(run(UpdateKind.BACKUP, UpdateStatus.DONE, null, null));
        assertTrue(entry.system());
        assertEquals("", entry.actorDiscordId());
        assertEquals("", entry.actorLabel());
    }

    @Test
    void theNightlyClockIsTheSystemToo() {
        final ActionEntry entry =
                ActionEntry.of(run(UpdateKind.BACKUP, UpdateStatus.DONE, "steward-worker (nightly)", null));
        assertTrue(entry.system());
    }

    @Test
    void aTrailingSnowflakeIsExtractedRatherThanShownAsText() {
        // "hm.till (594510749410525200)" is this deployment's own shape for "a name with the id
        // that goes with it" - identifiers-stay-in-the-popover.test.ts is what the extraction is
        // for: the frontend must never be handed a string with a raw snowflake sitting in it.
        final ActionEntry entry =
                ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, "hm.till (594510749410525200)", null));
        assertFalse(entry.system());
        assertEquals("594510749410525200", entry.actorDiscordId());
        assertEquals("", entry.actorLabel());
    }

    @Test
    void anOpaqueRequesterWithNoIdIsPlainTextRatherThanAGuessedPerson() {
        final ActionEntry entry =
                ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, "token-rotation-check", null));
        assertFalse(entry.system());
        assertEquals("", entry.actorDiscordId());
        assertEquals("token-rotation-check", entry.actorLabel());
    }

    // ---------------------------------------------------------------- from audit_log

    @Test
    void anAuditLineWithNoActorIsTheSystem() {
        final ActionEntry entry = ActionEntry.of(
                new AuditEntry(UUID.randomUUID(), FINISHED, "SETTLE", null, null, null, "settled by the poll loop"));
        assertTrue(entry.system());
        assertEquals("settled by the poll loop", entry.extent());
    }

    @Test
    void anAuditLinesActorIsAlreadyACleanDiscordId() {
        final ActionEntry entry = ActionEntry.of(new AuditEntry(
                UUID.randomUUID(),
                FINISHED,
                "GRANT_ACCESS",
                "594510749410525200",
                "594510749410525200",
                null,
                "30 days"));
        assertFalse(entry.system());
        assertEquals("594510749410525200", entry.actorDiscordId());
        assertEquals("30 days", entry.extent());
    }

    @Test
    void aBlankDetailFallsBackToTheActionItself() {
        final ActionEntry entry = ActionEntry.of(
                new AuditEntry(UUID.randomUUID(), FINISHED, "LINK", null, null, UUID.randomUUID(), null));
        assertEquals("LINK", entry.extent());
    }

    @Test
    @DisplayName("the moment leaves as text, not as the seconds and nanos an Instant is made of")
    void occurredIsSentAsATextTimestamp() {
        final ActionEntry entry =
                new ActionEntry("UPDATE", Instant.parse("2026-09-17T00:55:04.879Z"), "1/1 successful", "", "", true);
        final Object occurred = entry.json().get("occurred");
        assertInstanceOf(
                String.class,
                occurred,
                "api.ts types this field String and hands it to relative() - an object here is an"
                        + " invalid date in every row of the feed");
        assertEquals("2026-09-17T00:55:04.879Z", occurred);
    }
}
