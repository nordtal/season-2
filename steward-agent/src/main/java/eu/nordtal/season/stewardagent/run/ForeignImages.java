package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.Topology;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Plans the images of the services that are not ours and recreates the stale ones. */
final class ForeignImages {

    private ForeignImages() {}

    /**
     * Adds what the registries say about the images to the plan, so that a stale image is work.
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

        // An image that could not be compared is UNKNOWN, never current.
        if (!images.unverifiable().isEmpty()) {
            final List<String> unverifiable =
                    images.unverifiable().stream().sorted().toList();
            report = report.withNotes(Note.warning(Step.SOURCES, TEXTS.report().imagesUnverifiable())
                    .each(unverifiable));
        }

        // LOCAL is not work, but worth a note: unpublished code is overwritten by the next run.
        final List<String> local = images.local();
        if (!local.isEmpty()) {
            report = report.withNotes(
                    Note.warning(Step.SOURCES, TEXTS.report().imagesLocal()).each(local));
        }

        if (!images.reached()) {
            return report.withNote(
                    Note.warning(Step.SOURCES, TEXTS.report().imagesUnread(String.valueOf(images.message()))));
        }
        if (images.nothingCompared()) {
            return report.withNote(Note.warning(Step.SOURCES, TEXTS.report().imagesUncompared()));
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
                // {@code renewForeign} recreates it, and {@code stop} acts only on lines.
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
            report = report.withNotes(
                    Note.warning(Step.SOURCES, TEXTS.report().foreignNewer()).each(foreign));
        }
        return report;
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
     * A failure fails the line and the run. The old container keeps running.
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
                    UpdateReport.Facts.of(UpdateReport.Act.RECREATE)
                            .made(pull ? UpdateReport.Image.OUTDATED : UpdateReport.Image.KEPT)));
            progress.accept(report);
            final Steps.Step installing = run.record
                    .begin(RunStep.Kind.INSTALL, service)
                    .change(UpdateRun.IMAGE, null, null, pull ? RunStep.Image.PULLED : RunStep.Image.KEPT);
            final RedeployResult result = pull ? containers.deploy(service) : containers.recreate(service);
            UpdateRun.endBy(installing, result);
            if (result.triggered()) {
                asked.add(service);
                continue;
            }
            report = report.with(report.line(service)
                    .failed(UpdateReport.Facts.notRecreated(
                            pull ? UpdateReport.Image.OUTDATED : UpdateReport.Image.KEPT,
                            result.message(),
                            UpdateReport.Fallback.RUNNING,
                            null)));
            progress.accept(report);
        }
        // The caller writes the final report of the run.
        return asked.isEmpty() ? report : run.verify(report, asked, waiting);
    }
}
