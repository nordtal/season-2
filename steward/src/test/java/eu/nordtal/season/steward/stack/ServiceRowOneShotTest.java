package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.steward.WireJson;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A service that runs once is judged by its last exit, and a service a run moves is not judged at all. */
class ServiceRowOneShotTest {

    private static final AgentWire.Topology TOPOLOGY = new AgentWire.Topology(
            List.of(
                    new AgentWire.Service("migrate", null, false, false, null, null, null, null, true),
                    new AgentWire.Service("proxy", null, true, false)),
            List.of());

    @Test
    void aOneShotThatExitedCleanlyCarriesItsLastRunAndNoAlert() {
        final JsonObject row = row("migrate", "exited", 0, Set.of());

        assertTrue(row.get("oneShot").getAsBoolean());
        assertEquals(0, row.getAsJsonObject("lastRun").get("exitCode").getAsInt());
        assertEquals(
                "2026-10-06T01:00:09Z",
                row.getAsJsonObject("lastRun").get("finishedAt").getAsString());
        assertFalse(row.has("alert"), "migrate exits by design, so a clean exit is not an outage");
    }

    @Test
    void aOneShotThatFailedIsRed() {
        assertEquals("down", row("migrate", "exited", 1, Set.of()).get("alert").getAsString());
    }

    @Test
    void aLongRunningServiceCarriesNeitherField() {
        final JsonObject row = row("proxy", "exited", 0, Set.of());

        assertFalse(row.has("oneShot"), "absent, never false");
        assertFalse(row.has("lastRun"));
        assertEquals("down", row.get("alert").getAsString());
    }

    @Test
    void aStoppedServiceTheRunIsMovingIsNotRed() {
        assertFalse(row("proxy", "exited", 0, Set.of("proxy")).has("alert"));
    }

    /** The open run's services and those of a run that has just settled; an older run's are no longer its. */
    @Test
    void theOpenRunAndARunThatJustSettledAreWhatIsMoving() {
        final UpdateRequest settled = run(UpdateStatus.DONE, Instant.parse("2026-10-06T01:00:00Z"), "limbo", "proxy");
        final UpdateRequest open = run(UpdateStatus.RUNNING, null, "smp");

        assertEquals(Set.of("limbo", "proxy", "smp"), ServiceRows.moving(FakeDirectories.updates(settled, open)));
    }

    private static UpdateRequest run(
            final UpdateStatus status, final @Nullable Instant finished, final String... moving) {
        final Instant at = Instant.parse("2026-10-06T00:59:00Z");
        return new UpdateRequest(
                1, UpdateKind.UPDATE, status, Actor.STEWARD, at, at, at, List.of(moving), at, finished, null);
    }

    private static JsonObject row(
            final String service, final String state, final int exitCode, final Set<String> moving) {
        final AgentWire.Container container = new AgentWire.Container(
                service,
                "id-" + service,
                null,
                null,
                state,
                null,
                null,
                "2026-10-06T01:00:00Z",
                null,
                null,
                "2026-10-06T01:00:09Z",
                exitCode);
        return WireJson.gson()
                .toJsonTree(ServiceRows.describe(
                        container,
                        new ImageResult(true, Map.of(), Set.of(), null),
                        ServicesApi.Online.NONE,
                        Map.of(),
                        moving,
                        TOPOLOGY))
                .getAsJsonObject();
    }
}
