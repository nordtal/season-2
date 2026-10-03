package eu.nordtal.s2.steward.api;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.audit.AuditEntry;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.steward.WireJson;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The mapping from a raw row of either table to one {@link ActionEntry}, its extent and who is credited. */
class ActionEntryTest {

    private static final Instant REQUESTED = Instant.parse("2026-09-16T12:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-09-16T12:05:00Z");

    /** The bundle the page renders a label and an extent with. */
    private static final Messages ADMIN = Messages.load(ActionEntryTest.class.getClassLoader(), "messages/admin");

    private static String shown(final MessageRef message) {
        return ADMIN.format(Locale.ENGLISH, message);
    }

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
        assertEquals("2 of 3 successful", shown(entry.extent()));
        assertEquals("Update", shown(entry.label()));
    }

    @Test
    void nothingTouchedReadsTheReportsOwnHeadline() {
        final String report = "{\"stage\":\"NOTHING_TO_DO\",\"services\":["
                + "{\"service\":\"a\",\"state\":\"UNCHANGED\",\"changes\":[]}],\"notes\":[]}";
        final ActionEntry entry = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, Actor.HOST, report));
        assertEquals("Everything is already current", shown(entry.extent()));
    }

    @Test
    void unparseableResultFallsBackToTheStatusWord() {
        // An older steward wrote plain text, or the row has none yet.
        final ActionEntry done = ActionEntry.of(
                run(UpdateKind.UPDATE, UpdateStatus.DONE, Actor.HOST, "some plain text a very old steward wrote"));
        assertEquals("done", shown(done.extent()));

        final ActionEntry failed = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.FAILED, Actor.HOST, null));
        assertEquals("failed", shown(failed.extent()));
    }

    @Test
    void pendingAndRunningAreSaidRatherThanGuessedAt() {
        assertEquals(
                "waiting",
                shown(ActionEntry.of(new UpdateRequest(
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
                        .extent()));
        assertEquals(
                "running",
                shown(ActionEntry.of(new UpdateRequest(
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
                        .extent()));
    }

    @Test
    void aRunCarriesTheActorItWasAskedBy() {
        final Actor person = Actor.person(DiscordId.of("594510749410525200"));
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
                        UpdateKind.UPDATE, UpdateStatus.DONE, Actor.person(DiscordId.of("594510749410525200")), null))
                .wire();
        assertEquals(Actor.Kind.PERSON, person.actorKind());
        assertEquals("594510749410525200", person.actorId());

        final var host = ActionEntry.of(run(UpdateKind.UPDATE, UpdateStatus.DONE, Actor.HOST, null))
                .wire();
        assertEquals(Actor.Kind.HOST, host.actorKind());
        assertEquals("", host.actorId());
    }

    @Test
    void aJournalLineIsHeadedByItsActionAndShowsItsLine() {
        final ActionEntry entry = ActionEntry.of(new AuditEntry(
                UUID.randomUUID(),
                FINISHED,
                "GRANT_ACCESS",
                Actor.person(DiscordId.of("594510749410525200")),
                DiscordId.of("594510749410525200"),
                null,
                TEXTS.journal().grantAccess(30, Instant.parse("2026-11-02T10:00:00Z"))));
        assertEquals(Actor.person(DiscordId.of("594510749410525200")), entry.actor());
        assertEquals("Access granted", shown(entry.label()));
        assertEquals("30 days of access, until Nov 2, 2026.", shown(entry.extent()));
    }

    @Test
    void aLineUnderAnActionNoLongerListedIsHeadedByTheActionsName() {
        final ActionEntry entry = ActionEntry.of(new AuditEntry(
                UUID.randomUUID(),
                FINISHED,
                "RECREATE",
                Actor.STEWARD,
                null,
                null,
                TEXTS.journal().written("smp: recreated from the current image")));
        assertEquals("RECREATE", shown(entry.label()));
        assertEquals("smp: recreated from the current image", shown(entry.extent()));
    }

    @Test
    void theMomentLeavesAsTextNotAsTheSecondsAndNanosAnInstantIsMadeOf() {
        final ActionEntry entry = new ActionEntry(
                "UPDATE",
                Instant.parse("2026-09-17T00:55:04.879Z"),
                TEXTS.run().kind(UpdateKind.UPDATE),
                TEXTS.run().successful(1, 1),
                Actor.STEWARD);
        final JsonElement occurred =
                WireJson.gson().toJsonTree(entry.wire()).getAsJsonObject().get("occurred");
        assertTrue(
                occurred.isJsonPrimitive() && occurred.getAsJsonPrimitive().isString(),
                "the page hands this field to relative() - an object here is an invalid date in every row of the feed");
        assertEquals("2026-09-17T00:55:04.879Z", occurred.getAsString());
    }

    @Test
    void aMessageLeavesAsItsKeyAndTypedValues() {
        final ActionEntry entry = new ActionEntry(
                "UPDATE",
                FINISHED,
                TEXTS.run().kind(UpdateKind.UPDATE),
                TEXTS.run().successful(2, 3),
                Actor.STEWARD);
        assertEquals(
                "{\"key\":\"run.successful\",\"args\":{\"successful\":{\"kind\":\"number\",\"value\":2},"
                        + "\"total\":{\"kind\":\"number\",\"value\":3}}}",
                WireJson.gson()
                        .toJsonTree(entry.wire())
                        .getAsJsonObject()
                        .get("extent")
                        .toString(),
                "the page renders the extent through its web target, so it receives the message, never a sentence");
    }
}
