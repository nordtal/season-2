package eu.nordtal.s2.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link UpdatePlan#withoutServices}: what a run must not touch because somebody is holding it down.
 *
 * An empty hold list holds nothing, while an empty {@link UpdatePlan#onlyServices} scope means everything.
 */
class UpdatePlanHoldTest {

    private static Change on(final String service, final String artifact) {
        return new Change(service, artifact, Change.Status.OUTDATED, "old.jar", null, null);
    }

    private static UpdatePlan planOf(final List<Change> changes, final List<UpdatePlan.Unclaimed> unclaimed) {
        return new UpdatePlan(Instant.EPOCH, "v0.2.0", false, changes, unclaimed, List.of("a note"));
    }

    @Test
    void aHeldServiceIsTakenOutOfThePlanJarsAndUnclaimedAlike() {
        final UpdatePlan plan = planOf(
                List.of(on("smp", "smp"), on("smp", "packetevents"), on("limbo", "limbo")),
                List.of(
                        new UpdatePlan.Unclaimed("smp", "ByHand.jar"),
                        new UpdatePlan.Unclaimed("limbo", "AlsoByHand.jar")));

        final UpdatePlan left = plan.withoutServices(Set.of("limbo"));

        assertTrue(
                left.changes().stream().noneMatch(change -> "limbo".equals(change.service())),
                "a jar was installed into a service somebody had stopped on purpose - the run"
                        + " would then have to start it to verify, which is the one thing the hold"
                        + " forbids");
        assertEquals(2, left.changes().size());
        assertEquals(
                1,
                left.unclaimed().size(),
                "the report names a jar in the folder of a service this run deliberately skipped");
    }

    @Test
    void theResourcePackSurvivesAHoldNoHoldIsEverAboutIt() {
        // Unlike onlyServices, the pack has no service and no hold row names it, so a hold must not stop it.
        final Change pack = new Change(null, "pack", Change.Status.OUTDATED, "abc1234", null, null);
        final UpdatePlan plan = planOf(List.of(on("limbo", "limbo"), pack), List.of());

        final UpdatePlan left = plan.withoutServices(Set.of("limbo"));

        assertEquals(1, left.changes().size());
        assertEquals(
                null,
                left.changes().getFirst().service(),
                "the resource pack was dropped because a service was held down, and the pack"
                        + " belongs to no service");
    }

    @Test
    void nothingHeldIsNothingTakenOutTheEmptyListIsNotTheEmptyScope() {
        final UpdatePlan plan = planOf(List.of(on("smp", "smp"), on("limbo", "limbo")), List.of());

        assertSame(
                plan,
                plan.withoutServices(List.of()),
                "an empty hold list emptied the plan. That is onlyServices' meaning of empty, and"
                        + " it turns every ordinary run - which is every run, because holds are"
                        + " rare - into one that installs nothing.");
    }
}
