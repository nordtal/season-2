package eu.nordtal.s2.updater.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpdatePlan#onlyMissing()} - the filter a bootstrap runs through.
 *
 * <p>This is not a convenience method being covered for the sake of it. It is where "a container
 * that comes back up comes back on exactly the jars it was running" stops being a promise in a
 * comment and becomes something the type system enforces: {@code serve} installs a plan, and the
 * only plan it can build is one with no upgrades in it.</p>
 */
class UpdatePlanTest {

    private static Change change(final String artifact, final Change.Status status) {
        return new Change("smp", artifact, status, null, null, null);
    }

    private static UpdatePlan planOf(final Change... changes) {
        return new UpdatePlan(Instant.EPOCH, "v0.2.0", false, List.of(changes),
                List.of(new UpdatePlan.Unclaimed("smp", "SomethingSomebodyDropped.jar")));
    }

    @Test
    @DisplayName("an outdated jar is not something a bootstrap may touch")
    void anUpgradeIsNeverPartOfABootstrap() {
        final UpdatePlan plan = planOf(
                change("smp", Change.Status.OUTDATED),
                change("DisplayTags", Change.Status.MISSING));

        final UpdatePlan bootstrap = plan.onlyMissing();

        assertEquals(1, bootstrap.changes().size(),
                "onlyMissing() kept something that is not MISSING. An OUTDATED row here would make"
                        + " a crash restart at three in the morning move a version.");
        assertEquals("DisplayTags", bootstrap.changes().getFirst().artifact());
    }

    @Test
    @DisplayName("a volume that has everything gives a bootstrap nothing to do")
    void afullVolumeIsLeftAlone() {
        final UpdatePlan plan = planOf(
                change("smp", Change.Status.UP_TO_DATE),
                change("DisplayTags", Change.Status.OUTDATED));

        assertTrue(plan.onlyMissing().changes().isEmpty(),
                "a restart of a live network must install nothing at all");
    }

    @Test
    @DisplayName("a source that could not be reached is not mistaken for a missing file")
    void anUnreachableSourceIsNotMissing() {
        // The distinction matters at exactly one moment: GitHub is down while a container restarts.
        // UNRESOLVED means "we do not know", and installing on a guess would be the wrong half of
        // that. The report says so and the entrypoint one layer down names the empty folder.
        final UpdatePlan plan = planOf(
                Change.unresolved("smp", "PacketEvents", "Modrinth answered 503"),
                change("Chunky", Change.Status.MOUNT_MISSING));

        assertTrue(plan.onlyMissing().changes().isEmpty(),
                "onlyMissing() must not treat a failure as an empty volume");
    }

    @Test
    @DisplayName("the rest of the plan is carried over, so both reports describe the same volumes")
    void everythingElseAboutThePlanSurvives() {
        final UpdatePlan plan = planOf(change("DisplayTags", Change.Status.MISSING));
        final UpdatePlan bootstrap = plan.onlyMissing();

        assertEquals(plan.resolvedAt(), bootstrap.resolvedAt());
        assertEquals(plan.seasonTag(), bootstrap.seasonTag());
        assertEquals(plan.seasonPrerelease(), bootstrap.seasonPrerelease());
        assertEquals(plan.unclaimed(), bootstrap.unclaimed(),
                "unclaimed describes files nobody claimed and no run acts on it; dropping it would"
                        + " make the bootstrap's report disagree with `updater apply`'s for the"
                        + " same volumes");
    }
}
