package eu.nordtal.s2.stewardagent.run;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.internalapi.agent.Topology;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** What {@link Runner} puts into a plan about every image, and the images it renews once the rest is back. */
final class ForeignImages {

    private ForeignImages() {}

    /**
     * Puts what the registries say about the images into the plan, so that a stale image is work.
     * Never the agent's own, which a one-shot renews, one no label lets a run renew, or one nobody could check.
     *
     * @param scope the services this run is for, empty for the whole network; nothing outside it gets a line
     */
    static UpdateReport withImages(
            final UpdateReport planned,
            final ImageResult images,
            final AgentWire.Topology topology,
            final List<String> scope) {
        final List<String> renewedAfter = foreign(topology);
        final List<String> recreatable = topology.renewed(AgentWire.Renewal.RUN);
        UpdateReport report = planned;

        // Named first: an image that could not be compared is UNKNOWN, never silently current.
        final Optional<String> unverifiable = images.notCheckable();
        if (unverifiable.isPresent()) {
            report = report.withNote(unverifiable.get());
        }

        // LOCAL is not work, but worth a note: unpublished code is overwritten by the next run.
        final Optional<String> local = images.localImages();
        if (local.isPresent()) {
            report = report.withNote(local.get());
        }

        final Optional<String> nothing = images.nothingChecked();
        if (nothing.isPresent()) {
            return report.withNote(nothing.get());
        }

        final List<String> foreign = new ArrayList<>();
        for (final Map.Entry<String, ImageResult.State> entry :
                images.services().entrySet()) {
            if (entry.getValue() != ImageResult.State.OUTDATED) {
                continue;
            }
            final String service = entry.getKey();
            if (AgentWire.SERVICE.equals(service) || Topology.MIGRATE.equals(service)) {
                // The agent is handed over and renewed last; migrate runs from the install, made new by it.
                continue;
            }
            if (renewedAfter.contains(service)) {
                // Renewed later by #renewForeign; no line here, since `stop` acts on lines.
                continue;
            }
            if (!recreatable.contains(service)) {
                foreign.add(service);
                continue;
            }
            if (!scope.isEmpty() && !scope.contains(service)) {
                continue;
            }
            report = report.with(report.line(service).with(new UpdateReport.Change("image", null, "out of date")));
        }

        if (!foreign.isEmpty()) {
            report = report.withNote("The registry has a newer image for "
                    + String.join(", ", foreign) + ", which steward-agent does not own and never"
                    + " stops. Renew " + (foreign.size() == 1 ? "it" : "them")
                    + " with a redeploy of the project from the host.");
        }
        return report;
    }

    /**
     * The services renewed once the rest is back, in renewal order: postgres last, as the report goes through it.
     *
     * Each tag pins a major version, which a run must never change: Postgres will not start on another's data.
     */
    static List<String> foreign(final AgentWire.Topology topology) {
        return Stream.concat(
                        topology.renewed(AgentWire.Renewal.AFTER).stream(),
                        topology.renewed(AgentWire.Renewal.LAST).stream())
                .toList();
    }

    /** Those of {@link #foreign} that are {@code OUTDATED}, in its order. */
    static List<String> staleForeign(final AgentWire.Topology topology, final ImageResult images) {
        return foreign(topology).stream().filter(images::isOutdated).toList();
    }

    /**
     * Makes each of them again after the Minecraft services are back, pulling first when asked, then waits for health.
     *
     * A failure fails the line and the run, and rolls nothing back: the old container keeps running.
     */
    static UpdateReport renewForeign(
            final ContainerOps containers,
            final UpdateRun run,
            final UpdateReport before,
            final List<String> services,
            final boolean pull,
            final Consumer<UpdateReport> progress,
            final Waiting waiting) {
        if (services.isEmpty()) {
            return before;
        }
        UpdateReport report = before;
        final List<String> asked = new ArrayList<>();
        for (final String service : services) {
            // Written before the call, so a recreate that never returns leaves this as the report's last word.
            report = report.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.STARTING,
                    List.of(
                            pull
                                    ? new UpdateReport.Change("image", null, "out of date")
                                    : new UpdateReport.Change("container", null, "made again")),
                    pull ? "pulling its image and recreating the container" : "recreating the container"));
            progress.accept(report);
            final RedeployResult result = pull ? containers.deploy(service) : containers.recreate(service);
            if (result.triggered()) {
                asked.add(service);
                continue;
            }
            report = report.with(report.line(service)
                    .failed("the container could not be recreated: " + result.message()
                            + ". The one it had is still running."));
            progress.accept(report);
        }
        // This process writes the run's final report through postgres after returning from here.
        return asked.isEmpty() ? report : run.verify(report, asked, waiting);
    }
}
