package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link UpdatePlan#onlyServices} - what a run for one service is allowed to move.
 *
 * The filter is on the plan and not only on the report, and that is the whole reason this file exists:
 * {@code Runs#apply} installs what is in the plan. A scope that narrowed the report alone would give a run that
 * stops one server, says it is updating one server, and moves every jar in the network - which is worse than not
 * having the feature at all.
 */
class UpdatePlanScopeTest {

    private static Change on(final String service, final String artifact) {
        return new Change(service, artifact, Change.Status.OUTDATED, "old.jar", null, null);
    }

    private static UpdatePlan planOf(final List<Change> changes, final List<UpdatePlan.Unclaimed> unclaimed) {
        return new UpdatePlan(Instant.EPOCH, "v0.2.0", false, changes, unclaimed, List.of("a note"));
    }

    @Test
    void aScopeKeepsOnlyTheServicesItNames() {
        final UpdatePlan plan = planOf(
                List.of(on("smp", "smp"), on("smp", "packetevents"), on("limbo", "limbo")),
                List.of(
                        new UpdatePlan.Unclaimed("smp", "ByHand.jar"),
                        new UpdatePlan.Unclaimed("limbo", "AlsoByHand.jar")));

        final UpdatePlan scoped = plan.onlyServices(Set.of("smp"));

        assertEquals(
                2,
                scoped.changes().size(),
                "a run asked for smp carries a change for another service, which it would then"
                        + " install without ever having stopped that server");
        assertTrue(scoped.changes().stream().allMatch(change -> "smp".equals(change.service())));
        assertEquals(
                1,
                scoped.unclaimed().size(),
                "the report of a scoped run names a jar in a folder this run never looked in");
    }

    @Test
    void theResourcePackIsNotInAScopedRun() {
        // service == null is the pack: it lives in the proxy pack.yml, not a plugins folder; "update smp" leaves it.
        final Change pack = new Change(null, "pack", Change.Status.OUTDATED, "abc1234", null, null);
        final UpdatePlan plan = planOf(List.of(on("smp", "smp"), pack), List.of());

        final UpdatePlan scoped = plan.onlyServices(Set.of("smp"));

        assertEquals(
                1,
                scoped.changes().size(),
                "a scoped run carries the resource pack, which belongs to no service and was"
                        + " therefore never asked for");
        assertEquals("smp", scoped.changes().getFirst().service());
    }

    @Test
    void anEmptyScopeIsTheWholeNetworkUntouched() {
        // Nothing named means "do not narrow anything", not "no services" - the same meaning the column and API carry.
        final UpdatePlan plan = planOf(List.of(on("smp", "smp"), on("limbo", "limbo")), List.of());

        assertSame(
                plan,
                plan.onlyServices(List.of()),
                "an empty scope narrowed the plan. That turns a /update with nothing named into a"
                        + " run that does nothing at all.");
    }
}
