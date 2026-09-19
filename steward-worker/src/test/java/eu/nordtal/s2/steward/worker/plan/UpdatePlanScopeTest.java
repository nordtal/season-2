package eu.nordtal.s2.steward.worker.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpdatePlan#onlyServices} - what a run for one service is allowed to move (season-2-ops/127).
 *
 * <p>The filter is on the <b>plan</b> and not only on the report, and that is the whole reason this
 * file exists: {@code Runs#apply} installs what is in the plan. A scope that narrowed the report
 * alone would give a run that stops one server, says it is updating one server, and moves every jar
 * in the network - which is worse than not having the feature at all.</p>
 */
class UpdatePlanScopeTest {

    private static Change on(final String service, final String artifact) {
        return new Change(service, artifact, Change.Status.OUTDATED, "old.jar", null, null);
    }

    private static UpdatePlan planOf(final List<Change> changes,
                                     final List<UpdatePlan.Unclaimed> unclaimed) {
        return new UpdatePlan(Instant.EPOCH, "v0.2.0", false, changes, unclaimed, List.of("a note"));
    }

    @Test
    @DisplayName("a scope keeps only the services it names")
    void keepsOnlyTheNamedServices() {
        final UpdatePlan plan = planOf(
                List.of(on("smp", "smp"), on("smp", "chunky"), on("limbo", "limbo")),
                List.of(new UpdatePlan.Unclaimed("smp", "ByHand.jar"),
                        new UpdatePlan.Unclaimed("limbo", "AlsoByHand.jar")));

        final UpdatePlan scoped = plan.onlyServices(Set.of("smp"));

        assertEquals(2, scoped.changes().size(),
                "a run asked for smp carries a change for another service, which it would then"
                        + " install without ever having stopped that server");
        assertTrue(scoped.changes().stream().allMatch(change -> "smp".equals(change.service())));
        assertEquals(1, scoped.unclaimed().size(),
                "the report of a scoped run names a jar in a folder this run never looked in");
    }

    @Test
    @DisplayName("the resource pack is not in a scoped run")
    void theResourcePackFallsOut() {
        // service == null is the pack: it is written into the proxy's pack.yml rather than
        // installed into anybody's plugins folder. "Update smp" must not rewrite it.
        final Change pack = new Change(null, "pack", Change.Status.OUTDATED, "abc1234", null, null);
        final UpdatePlan plan = planOf(List.of(on("smp", "smp"), pack), List.of());

        final UpdatePlan scoped = plan.onlyServices(Set.of("smp"));

        assertEquals(1, scoped.changes().size(),
                "a scoped run carries the resource pack, which belongs to no service and was"
                        + " therefore never asked for");
        assertEquals("smp", scoped.changes().getFirst().service());
    }

    @Test
    @DisplayName("an empty scope is the whole network, untouched")
    void emptyIsEverything() {
        // The same meaning the column, the API and the button all carry: nothing named is not "no
        // services", it is "do not narrow anything". Every run before this existed was one.
        final UpdatePlan plan = planOf(List.of(on("smp", "smp"), on("limbo", "limbo")), List.of());

        assertSame(plan, plan.onlyServices(List.of()),
                "an empty scope narrowed the plan. That turns a /update with nothing named into a"
                        + " run that does nothing at all.");
    }
}
