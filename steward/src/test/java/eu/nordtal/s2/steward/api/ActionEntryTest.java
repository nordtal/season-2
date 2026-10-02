package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.audit.AuditEntry;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.steward.WireJson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The mapping from a raw row of either table to one {@link ActionEntry}, its extent and who is credited. */
class ActionEntryTest {

    private static final Instant REQUESTED = Instant.parse("2026-09-16T12:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-09-16T12:05:00Z");

    private static UpdateRequest run(
            final UpdateKind kind, final UpdateStatus status, final Actor actor, final String result) {
        return new UpdateRequest(
                1L,
                kind,
                status,
                actor,
                REQUESTED,
                REQUESTED,
                null,
                List.of(),
                REQUESTED,
                status.isFinished() ? FINISHED : null,
                result);
    }

    @Test
    void countsHealthyAndSavedLinesAgainstEverythingTouched() {
        // One HEALTHY, SAVED, FAILED and UNCHANGED each, plus an untouched PLANNED: total 3, successful 2.
        final String report = "{\"stage\":\"DONE\",\"services\":["
                + "{\"service\":\"a\",\"state\":\"HEALTHY\",\"changes\":[]},"
                + "{\"service\":\"b\",\"state\":\"SAVED\",\"changes\":[]},"
                + "{\"service\":\"c\",\"state\":\"FAILED\",\"changes\":[]},"
                + "{\"service\":\"d\",\"state\":\"UNCHANGED\",\"changes\":[]},"
                + "{\"service\":\"e\",\"state\":\"PLANNED\",\"changes\":[]}"
                + "],\"notes\":[]}";
        final ActionEntry entry = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, Actor.HOST, report));
        assertEquals("2/3 successful", entry.extent());
    }

    @Test
    void nothingTouchedReadsTheReportsOwnHeadline() {
        final String report = "{\"stage\":\"NOTHING_TO_DO\",\"services\":["
                + "{\"service\":\"a\",\"state\":\"UNCHANGED\",\"changes\":[]}],\"notes\":[]}";
        final ActionEntry entry = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, Actor.HOST, report));
        assertEquals("Everything is already current", entry.extent());
    }

    @Test
    void unparseableResultFallsBackToTheStatusWord() {
        // An older steward wrote plain text, or the row has none yet.
        final ActionEntry done = ActionEntry.of(
                run(UpdateKind.UPDATE, UpdateStatus.DONE, Actor.HOST, "some plain text a very old steward wrote"));
        assertEquals("done", done.extent());

        final ActionEntry failed = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.FAILED, Actor.HOST, null));
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
                                Actor.HOST,
                                REQUESTED,
                                REQUESTED,
                                null,
                                List.of(),
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
                                Actor.HOST,
                                REQUESTED,
                                REQUESTED,
                                null,
                                List.of(),
                                REQUESTED,
                                null,
                                null))
                        .extent());
    }

    @Test
    void aRunCarriesTheActorItWasAskedBy() {
        final Actor person = Actor.person(DiscordId.of("300000000000000077"));
        assertEquals(
                person,
                ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, person, null))
                        .actor());
        assertEquals(
                Actor.STEWARD,
                ActionEntry.of(run(UpdateKind.BACKUP, UpdateStatus.DONE, Actor.STEWARD, null))
                        .actor());
    }

    @Test
    void theBrowserReadsTheActorAsAKindAndAnIdThatIsNeverNull() {
        final var person = ActionEntry.of(run(
                        UpdateKind.UPDATE, UpdateStatus.DONE, Actor.person(DiscordId.of("300000000000000077")), null))
                .wire();
        assertEquals(Actor.Kind.PERSON, person.actorKind());
        assertEquals("300000000000000077", person.actorId());

        final var host = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, Actor.HOST, null))
                .wire();
        assertEquals(Actor.Kind.HOST, host.actorKind());
        assertEquals("", host.actorId());
    }

    @Test
    void aCarriedLineStillShowsItsSentence() {
        final ActionEntry entry = ActionEntry.of(new AuditEntry(
                UUID.randomUUID(),
                FINISHED,
                "SETTLE",
                Actor.STEWARD,
                null,
                null,
                facts("detail", "settled by the poll loop")));
        assertEquals(Actor.STEWARD, entry.actor());
        assertEquals("settled by the poll loop", entry.extent());
    }

    @Test
    void aTypedLineCarriesItsActorAndNamesItsAction() {
        final ActionEntry entry = ActionEntry.of(new AuditEntry(
                UUID.randomUUID(),
                FINISHED,
                "GRANT_ACCESS",
                Actor.person(DiscordId.of("300000000000000077")),
                DiscordId.of("300000000000000077"),
                null,
                facts("days", "30")));
        assertEquals(Actor.person(DiscordId.of("300000000000000077")), entry.actor());
        assertEquals("GRANT_ACCESS", entry.extent(), "the values are rendered by the page, not by this list");
    }

    @Test
    void aBlankDetailFallsBackToTheActionItself() {
        final ActionEntry entry = ActionEntry.of(
                new AuditEntry(UUID.randomUUID(), FINISHED, "LINK", Actor.STEWARD, null, UUID.randomUUID(), facts()));
        assertEquals("LINK", entry.extent());
    }

    @Test
    void theMomentLeavesAsTextNotAsTheSecondsAndNanosAnInstantIsMadeOf() {
        final ActionEntry entry =
                new ActionEntry("UPDATE", Instant.parse("2026-09-17T00:55:04.879Z"), "1/1 successful", Actor.STEWARD);
        final JsonElement occurred =
                WireJson.gson().toJsonTree(entry.wire()).getAsJsonObject().get("occurred");
        assertTrue(
                occurred.isJsonPrimitive() && occurred.getAsJsonPrimitive().isString(),
                "the page hands this field to relative() - an object here is an invalid date in every row of the feed");
        assertEquals("2026-09-17T00:55:04.879Z", occurred.getAsString());
    }

    private static JsonObject facts(final String... pairs) {
        final JsonObject facts = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            facts.addProperty(pairs[i], pairs[i + 1]);
        }
        return facts;
    }
}
