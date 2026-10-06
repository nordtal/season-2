package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.stewardagent.plan.Change;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which builds made on this host an update run would replace, so that it asks before it does. */
class LocalBuildsTest {

    private static final ImageResult IMAGES = ImageResult.of(Map.of())
            .withLocalBuilds(Map.of(
                    "steward", new ImageResult.LocalBuild("ghcr.io/nordtal/steward:0.17.0", List.of()),
                    "smp", new ImageResult.LocalBuild(null, List.of("smp-0.17.0.jar"))));

    @Test
    void anImageBuiltHereGoesWhenItsContainerIsMadeAgainAndStaysOtherwise() {
        final UpdateReport stewardMoves = UpdateReport.at(UpdateReport.Stage.RESOLVING)
                .with(UpdateReport.at(UpdateReport.Stage.RESOLVING)
                        .line("steward")
                        .with(new UpdateReport.Change("steward", "0.16.0", "0.17.0")));

        assertEquals(List.of("steward"), replaced(plan(), stewardMoves, List.of(), false, List.of(), List.of()));
        assertEquals(List.of(), replaced(plan(), nothing(), List.of(), false, List.of(), List.of()));
        assertEquals(
                List.of("steward"),
                replaced(plan(), nothing(), List.of("steward"), false, List.of(), List.of()),
                "a renewed foreign image is a container made again too");
    }

    @Test
    void anOutdatedAgentBuiltHereGoesWithTheOneShotThatRenewsIt() {
        final ImageResult agent = ImageResult.of(Map.of("steward-agent", ImageResult.State.OUTDATED))
                .withLocalBuilds(Map.of(
                        "steward-agent",
                        new ImageResult.LocalBuild("ghcr.io/nordtal/steward-agent:0.17.0", List.of())));

        assertEquals(
                List.of("steward-agent"),
                LocalBuilds.replaced(agent, plan(), nothing(), List.of(), false, List.of(), List.of()));
    }

    @Test
    void aNewerReleaseMakesEveryContainerAgainSoEveryImageBuiltHereGoes() {
        assertEquals(List.of("steward"), replaced(plan(), nothing(), List.of(), true, List.of(), List.of()));
    }

    @Test
    void aLocalJarGoesOnlyWhenItsOwnRowIsWork() {
        final UpdatePlan smpOutdated =
                plan(new Change("smp", "smp", Change.Status.OUTDATED, "smp-0.17.0.jar", null, null));
        final UpdatePlan smpCurrent =
                plan(new Change("smp", "smp", Change.Status.UP_TO_DATE, "smp-0.17.0.jar", null, null));
        final UpdatePlan otherJar =
                plan(new Change("smp", "chunky", Change.Status.OUTDATED, "Chunky-1.4.jar", null, null));

        assertEquals(List.of("smp"), replaced(smpOutdated, nothing(), List.of(), false, List.of(), List.of()));
        assertEquals(List.of(), replaced(smpCurrent, nothing(), List.of(), false, List.of(), List.of()));
        assertEquals(List.of(), replaced(otherJar, nothing(), List.of(), false, List.of(), List.of()));
    }

    @Test
    void aServiceOutsideTheScopeOrHeldIsNotReplacedByThisRun() {
        assertEquals(List.of(), replaced(plan(), nothing(), List.of(), true, List.of("limbo"), List.of()));
        assertEquals(List.of(), replaced(plan(), nothing(), List.of(), true, List.of(), List.of("steward")));
    }

    private static List<String> replaced(
            final UpdatePlan plan,
            final UpdateReport planned,
            final List<String> renewed,
            final boolean newer,
            final List<String> scope,
            final List<String> held) {
        return LocalBuilds.replaced(IMAGES, plan, planned, renewed, newer, scope, held);
    }

    private static UpdateReport nothing() {
        return UpdateReport.at(UpdateReport.Stage.RESOLVING);
    }

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(Instant.EPOCH, "v0.17.0", false, List.of(changes), List.of(), List.of());
    }
}
