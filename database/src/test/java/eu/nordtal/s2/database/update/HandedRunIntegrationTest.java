package eu.nordtal.s2.database.update;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.messages.Refused;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A run handed to a one-shot steward-agent, against a real PostgreSQL running the real migrations.
 *
 * The row stays open while the one-shot runs and is the lock; tests skip without Docker.
 */
class HandedRunIntegrationTest {

    private static final String ONE_SHOT = "nordtal-s2-steward-agent-run";

    private final UpdateDirectory updates =
            UpdateDirectory.using(TestDatabase.fresh().dataSource());

    /**
     * Checks that a run survives the agent's start while its one-shot runs, and not once the one-shot is gone.
     *
     * The agent the one-shot makes last starts while the row is still open; failing it would end the lock too early.
     */
    @Test
    void aRunHandedToAOneShotThatStillRunsIsNoOrphan() {
        final UpdateRequest apply = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.handOver(apply.id(), ONE_SHOT));
        assertEquals(Optional.of(ONE_SHOT), updates.runnerOf(apply.id()));
        assertEquals(Optional.of(ONE_SHOT), updates.handedTo());

        assertEquals(0, updates.settleOrphans(TEXTS.report().words("Killed mid-run"), ONE_SHOT::equals));
        assertEquals(
                UpdateStatus.RUNNING, updates.find(apply.id()).orElseThrow().status());
        assertThrows(
                Refused.class,
                () -> updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO),
                "the handed row still holds the lock");

        assertEquals(1, updates.settleOrphans(TEXTS.report().words("Killed mid-run"), runner -> false));
        assertEquals(UpdateStatus.FAILED, updates.find(apply.id()).orElseThrow().status());
        assertEquals(Optional.empty(), updates.runnerOf(apply.id()), "a settled row has no runner");
        assertEquals(Optional.empty(), updates.handedTo(), "a settled row is handed to nobody");
        assertFalse(updates.handOver(apply.id(), "late"), "a settled row cannot be handed over");
    }
}
