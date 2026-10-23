package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Plans the images of the services that are not ours and recreates the stale ones. */
final class ForeignImages {

    private ForeignImages() {}

    /**
     * Adds what the registries say about the images to the plan, so that a stale image is work.
     *
     * @param scope the services this run is for, empty for the whole network; nothing outside it gets work
     */
    static Work withImages(
            final Work planned, final ImageResult images, final AgentWire.Topology topology, final List<String> scope) {
        final List<String> renewedAfter = foreign(topology);
        final List<String> recreatable = topology.renewed(AgentWire.Renewal.RUN);
        // An image that could not be compared is never current; a local one is overwritten by the next run.
        Work work = planned.with(Work.Finding.each(
                        images.unverifiable().stream().sorted().toList(),
                        RunStep.Outcome.WARNING,
                        RunStep.Reason.UNVERIFIABLE))
                .with(Work.Finding.each(images.local(), RunStep.Outcome.WARNING, RunStep.Reason.LOCAL_BUILD));

        if (!images.reached() || images.nothingCompared()) {
            return work.with(List.of(new Work.Finding(null, RunStep.Outcome.WARNING, RunStep.Reason.UNVERIFIABLE)));
        }

        final List<String> foreign = new ArrayList<>();
        for (final Map.Entry<String, ImageResult.State> entry :
                images.services().entrySet()) {
            if (entry.getValue() != ImageResult.State.OUTDATED) {
                continue;
            }
            final String service = entry.getKey();
            if (AgentWire.SERVICE.equals(service) || Topology.MIGRATE.equals(service)) {
                // The one-shot recreates the agent last, and migrate runs from the install.
                continue;
            }
            if (renewedAfter.contains(service)) {
                // {@code renewForeign} recreates it, and {@code stop} acts only on what moves.
                continue;
            }
            if (!recreatable.contains(service)) {
                foreign.add(service);
                continue;
            }
            if (!scope.isEmpty() && !scope.contains(service)) {
                continue;
            }
            // An out-of-date image is always pulled.
            work = work.with(service, new RunStep.Change(UpdateRun.IMAGE, null, null, RunStep.Image.PULLED));
        }
        return work.with(Work.Finding.each(foreign, RunStep.Outcome.WARNING, RunStep.Reason.FOREIGN_NEWER));
    }

    /**
     * Returns the services recreated once the rest is back, postgres last.
     *
     * Each tag pins a major version, which a run must never change: Postgres will not start on another's data.
     */
    static List<String> foreign(final AgentWire.Topology topology) {
        return Stream.concat(
                        topology.renewed(AgentWire.Renewal.AFTER).stream(),
                        topology.renewed(AgentWire.Renewal.LAST).stream())
                .toList();
    }

    static List<String> staleForeign(final AgentWire.Topology topology, final ImageResult images) {
        return foreign(topology).stream().filter(images::isOutdated).toList();
    }

    /**
     * Recreates the stale services once Minecraft is back, pulling first when asked, and waits for health.
     *
     * A failure fails the step and the run. The old container keeps running.
     */
    static void renewForeign(
            final ContainerOps containers,
            final UpdateRun run,
            final List<String> services,
            final boolean pull,
            final Waiting waiting) {
        final List<String> asked = new ArrayList<>();
        for (final String service : services) {
            final Steps.Step installing = run.record
                    .begin(RunStep.Kind.INSTALL, service)
                    .change(UpdateRun.IMAGE, null, null, pull ? RunStep.Image.PULLED : RunStep.Image.KEPT);
            final RedeployResult result = pull ? containers.deploy(service) : containers.recreate(service);
            UpdateRun.endBy(installing, result);
            if (result.triggered()) {
                asked.add(service);
            }
        }
        if (!asked.isEmpty()) {
            run.verify(asked, waiting);
        }
    }
}
