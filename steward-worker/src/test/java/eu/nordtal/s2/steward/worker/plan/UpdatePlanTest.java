package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link UpdatePlan#onlyMissing()}, the filter a bootstrap runs through, so it installs no upgrades. */
class UpdatePlanTest {

    private static Change change(final String artifact, final Change.Status status) {
        return new Change("smp", artifact, status, null, null, null);
    }

    private static UpdatePlan planOf(final Change... changes) {
        return new UpdatePlan(
                Instant.EPOCH,
                "v0.2.0",
                false,
                List.of(changes),
                List.of(new UpdatePlan.Unclaimed("smp", "SomethingSomebodyDropped.jar")),
                List.of());
    }

    @Test
    void anOutdatedJarIsNotSomethingABootstrapMayTouch() {
        final UpdatePlan plan =
                planOf(change("smp", Change.Status.OUTDATED), change("DisplayTags", Change.Status.MISSING));

        final UpdatePlan bootstrap = plan.onlyMissing();

        assertEquals(
                1,
                bootstrap.changes().size(),
                "onlyMissing() kept something that is not MISSING. An OUTDATED row here would make"
                        + " a crash restart at three in the morning move a version.");
        assertEquals("DisplayTags", bootstrap.changes().getFirst().artifact());
    }

    @Test
    void aVolumeThatHasEverythingGivesABootstrapNothingToDo() {
        final UpdatePlan plan =
                planOf(change("smp", Change.Status.UP_TO_DATE), change("DisplayTags", Change.Status.OUTDATED));

        assertTrue(plan.onlyMissing().changes().isEmpty(), "a restart of a live network must install nothing at all");
    }

    @Test
    void aSourceThatCouldNotBeReachedIsNotMistakenForAMissingFile() {
        // GitHub being down means UNRESOLVED, not missing; onlyMissing() must count that as work, not an empty volume.
        final UpdatePlan plan = planOf(
                Change.unresolved("smp", "PacketEvents", "Modrinth answered 503"),
                change("VoiceChat", Change.Status.MOUNT_MISSING));

        final UpdatePlan bootstrap = plan.onlyMissing();

        assertFalse(bootstrap.hasMissing(), "onlyMissing() must not treat a failure as an empty volume to fill");
        assertEquals(
                2,
                bootstrap.changes().size(),
                "the rows a bootstrap cannot act on still have to reach the report - dropping them"
                        + " is what let a run with eight unresolved artefacts close with"
                        + " \"Everything asked for was done.\"");
    }

    @Test
    void b4TheUnresolvedRowsSurviveSoTheReportCannotClaimUnbrokenSuccess() {
        // A resolved plugin next to an unresolved season jar must not report success while a jar goes uninstalled.
        final UpdatePlan plan = planOf(
                Change.unresolved("smp", "season", "could not read nordtal/season-2@latest: HTTP 403"),
                change("PacketEvents", Change.Status.MISSING),
                change("VoiceChat", Change.Status.MISSING));

        final UpdatePlan bootstrap = plan.onlyMissing();

        assertTrue(bootstrap.hasMissing(), "two jars really are missing");
        assertTrue(
                bootstrap.hasFailures(),
                "the outage has to survive into the plan the bootstrap installs and reports on;"
                        + " without it Applier's all-or-nothing rule cannot see the service is"
                        + " incomplete and installs the two that resolved");
        assertEquals(3, bootstrap.changes().size());

        assertTrue(
                Report.render(bootstrap).contains("could not be checked"),
                "a rendered bootstrap plan has to say that part of the picture is missing");
    }

    @Test
    void theRestOfThePlanIsCarriedOverSoBothReportsDescribeTheSameVolumes() {
        final UpdatePlan plan = planOf(change("DisplayTags", Change.Status.MISSING));
        final UpdatePlan bootstrap = plan.onlyMissing();

        assertEquals(plan.resolvedAt(), bootstrap.resolvedAt());
        assertEquals(plan.seasonTag(), bootstrap.seasonTag());
        assertEquals(plan.seasonPrerelease(), bootstrap.seasonPrerelease());
        assertEquals(
                plan.unclaimed(),
                bootstrap.unclaimed(),
                "unclaimed describes files nobody claimed and no run acts on it; dropping it would"
                        + " make the bootstrap's report disagree with `steward-worker apply`'s for"
                        + " the same volumes");
    }
}
