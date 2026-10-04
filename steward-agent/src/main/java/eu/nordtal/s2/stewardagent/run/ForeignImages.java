package eu.nordtal.s2.stewardagent.run;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.internalapi.agent.Topology;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        if (!images.unverifiable().isEmpty()) {
            final List<String> unverifiable =
                    images.unverifiable().stream().sorted().toList();
            report = report.withNote(TEXTS.report().imagesUnverifiable(unverifiable, unverifiable.size()));
        }

        // LOCAL is not work, but worth a note: unpublished code is overwritten by the next run.
        final List<String> local = images.local();
        if (!local.isEmpty()) {
            report = report.withNote(TEXTS.report().imagesLocal(local, local.size()));
        }

        if (!images.reached()) {
            return report.withNote(TEXTS.report().imagesUnread(String.valueOf(images.message())));
        }
        if (images.nothingCompared()) {
            return report.withNote(TEXTS.report().imagesUncompared());
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
            report = report.with(report.line(service)
                    .with(UpdateReport.Change.told("image", TEXTS.report().imageOutdated())));
        }

        if (!foreign.isEmpty()) {
            report = report.withNote(TEXTS.report().foreignNewer(foreign, foreign.size()));
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
                                    ? UpdateReport.Change.told(
                                            "image", TEXTS.report().imageOutdated())
                                    : UpdateReport.Change.told(
                                            "container", TEXTS.report().madeAgain(false))),
                    TEXTS.report().recreating(pull)));
            progress.accept(report);
            final RedeployResult result = pull ? containers.deploy(service) : containers.recreate(service);
            if (result.triggered()) {
                asked.add(service);
                continue;
            }
            report = report.with(report.line(service).failed(TEXTS.report().foreignNotRecreated(result.message())));
            progress.accept(report);
        }
        // This process writes the run's final report through postgres after returning from here.
        return asked.isEmpty() ? report : run.verify(report, asked, waiting);
    }
}
