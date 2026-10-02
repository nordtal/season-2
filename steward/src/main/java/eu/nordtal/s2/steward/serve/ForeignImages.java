package eu.nordtal.s2.steward.serve;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.internalapi.agent.ContainerOps;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.steward.plan.Topology;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The three images this project does not build itself, and what {@link Runner} puts into a plan about all of them. */
final class ForeignImages {

    private ForeignImages() {}

    /**
     * Puts what the registries say about the images into the plan, so that a stale image is work.
     *
     * Never claims steward's own image, a service outside {@link Topology}, or an image nobody could check.
     */
    static UpdateReport withImages(final UpdateReport planned, final ImageResult images) {
        return withImages(planned, images, List.of());
    }

    /**
     * Adds image rows to a plan.
     *
     * @param scope the services this run is for, empty for the whole network; nothing outside it gets a line
     */
    static UpdateReport withImages(final UpdateReport planned, final ImageResult images, final List<String> scope) {
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
            if (Topology.STEWARD.equals(service)) {
                report = report.withNote("Steward's own image is out of date. Nothing here"
                        + " can renew it: the recreate would take this process down in the middle"
                        + " of its own run. Redeploy the project from the host when the network is"
                        + " quiet - that is the one thing in this deployment which still needs a"
                        + " hand.");
                continue;
            }
            if (FOREIGN_IMAGES.contains(service)) {
                // Renewed later by #renewForeign; no line here, since `stop` acts on lines.
                continue;
            }
            if (!RECREATABLE.contains(service)) {
                foreign.add(service);
                continue;
            }
            if (!scope.isEmpty() && !scope.contains(service)) {
                continue;
            }
            report = report.with(report.line(service).with(new UpdateReport.Change("image", null, "newer image")));
        }

        if (!foreign.isEmpty()) {
            report = report.withNote("The registry has a newer image for "
                    + String.join(", ", foreign) + ", which steward does not own and never"
                    + " stops. Renew " + (foreign.size() == 1 ? "it" : "them")
                    + " with a redeploy of the project from the host.");
        }
        return report;
    }

    /**
     * The three images nobody here builds, in renewal order, with postgres last since the report goes through it.
     *
     * Each tag pins a major version, which a run must never change: Postgres will not start on another's data.
     */
    static final List<String> FOREIGN_IMAGES = List.of("caddy", "pack-host", "postgres");

    /** The services a run may pull an image for and recreate: everything it already stops, and nothing else. */
    static final Set<String> RECREATABLE = Stream.concat(
                    Topology.SERVICES.stream().map(Topology.Service::name), Stream.of(Topology.DISCORD_BOT))
            .collect(Collectors.toUnmodifiableSet());

    /** The foreign images that are {@code OUTDATED}, in {@link #FOREIGN_IMAGES}'s order. */
    static List<String> staleForeign(final ImageResult images) {
        return FOREIGN_IMAGES.stream().filter(images::isOutdated).toList();
    }

    /**
     * Pulls and recreates each of them after the Minecraft services are back, then waits for health.
     *
     * A failure fails the line and the run, and rolls nothing back: the old container keeps running.
     */
    static UpdateReport renewForeign(
            final ContainerOps containers,
            final UpdateRun run,
            final UpdateReport before,
            final List<String> services,
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
                    List.of(new UpdateReport.Change("image", null, "newer image")),
                    "pulling its image and recreating the container"));
            progress.accept(report);
            final RedeployResult result = containers.deploy(service);
            if (result.triggered()) {
                asked.add(service);
                continue;
            }
            report = report.with(report.line(service)
                    .failed("its image is out of date and the"
                            + " container could not be recreated: " + result.message()
                            + ". It is still running the image it had."));
            progress.accept(report);
        }
        // This process writes the run's final report through postgres after returning from here.
        return asked.isEmpty() ? report : run.verify(report, asked, waiting);
    }
}
