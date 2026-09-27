package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.plan.Topology;
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
     * Why it has to be in the plan and not in the starting step: {@code isWork()} decides whether anybody is counted
     * down and whether a server is stopped at all. A service whose jars are current and whose image is not would
     * otherwise fall out at {@code NOTHING_TO_DO} - every run reporting the network current while {@code entrypoint.sh}
     * and the JRE stayed on whatever was pulled at the last deploy, which is exactly the state this was written to
     * end. Adding the row here also means the change is in the embed a person confirms, rather than appearing after
     * they said yes to something else.
     *
     * What it will not claim:
     *
     * - Steward-worker's own image. The recreate would take this process down mid-run. It is a note, and moving it
     *   needs a redeploy of the project from outside this process - the one thing in this deployment that still does.
     * - A service this worker does not own. {@code postgres} and the backup sidecar are not in {@link Topology}, are
     *   never stopped by this sequence, and recreating one behind a report that does not mention it would be the
     *   worst kind of surprise. They are named in a note instead.
     * - Anything whose registry could not be asked. {@link ImageResult} keeps "nobody has looked" apart from "up to
     *   date", and only the first of those is ever silent here.
     */
    static UpdateReport withImages(final UpdateReport planned, final ImageResult images) {
        return withImages(planned, images, List.of());
    }

    /**
     * @param scope the services this run is for, empty for the whole network. A service outside the
     *              scope is never given a line here: a line is what makes a server get stopped, and
     *              a run that says "smp" must not take the proxy down because its image moved.
     */
    static UpdateReport withImages(final UpdateReport planned, final ImageResult images, final List<String> scope) {
        UpdateReport report = planned;

        // Named first: a service whose image could not be compared is UNKNOWN, silently read as current otherwise.
        final Optional<String> unverifiable = images.notCheckable();
        if (unverifiable.isPresent()) {
            report = report.withNote(unverifiable.get());
        }

        // LOCAL is not work, but worth a note: a service running unpublished code is overwritten by the next run.
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
            if (Topology.STEWARD_WORKER.equals(service)) {
                report = report.withNote("Steward-worker's own image is out of date. Nothing here"
                        + " can renew it: the recreate would take this process down in the middle"
                        + " of its own run. Redeploy the project from the host when the network is"
                        + " quiet - that is the one thing in this deployment which still needs a"
                        + " hand.");
                continue;
            }
            if (FOREIGN_IMAGES.contains(service)) {
                // Renewed later by #renewForeign; no line here, since a line is what `stop` would act on.
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
                    + String.join(", ", foreign) + ", which steward-worker does not own and never"
                    + " stops. Renew " + (foreign.size() == 1 ? "it" : "them")
                    + " with a redeploy of the project from the host.");
        }
        return report;
    }

    /**
     * The three images in this project that nobody here builds, in the order they are renewed.
     *
     * Only within the major, and the tag is what guarantees it: {@code postgres:17-alpine} cannot become 18 while
     * nobody edits the tag, and the same holds for {@code caddy:2-alpine} and {@code nginx:alpine}. So the guarantee
     * needs no code at all - and that is exactly why the run must not touch those tags. The reason the boundary
     * matters is worth having next to the list: Postgres does not start on a data directory written by the previous
     * major. A major jump would not update the database, it would stop it, and getting back out is a dump and a
     * restore - a planned procedure, not something a run discovers.
     *
     * The order, and why postgres is last: Ordered, not a set. {@code caddy} and {@code pack-host} cost a web page a
     * second and no player anything. {@code postgres} is this process's own lifeline: every progress write goes
     * through it, and the final report of the run is written after this class returns. So it is renewed last, and
     * {@link eu.nordtal.s2.steward.worker.serve.UpdateRun#verify} waits for it to be healthy again before anything
     * else happens - otherwise the report of a run that worked would be the thing that got lost.
     *
     * {@code pack-host} is in the {@code devpack} profile and simply is not there on a production selection; a service
     * the daemon has no container for is never named by {@link ImageResult} and therefore never renewed. Listing it
     * costs nothing and keeps the list the same on both kinds of host.
     */
    static final List<String> FOREIGN_IMAGES = List.of("caddy", "pack-host", "postgres");

    /**
     * The services a run may pull an image for and recreate: everything it already stops, and nothing else.
     *
     * Steward-worker is absent for the reason {@link ContainerOps#recreate} gives, and so is anything outside
     * {@link Topology} - a sequence that recreates a container it never stopped and never mentioned is one nobody can
     * predict from the report they confirmed.
     */
    static final Set<String> RECREATABLE = Stream.concat(
                    Topology.SERVICES.stream().map(Topology.Service::name), Stream.of(Topology.DISCORD_BOT))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * The foreign images that are actually behind, in {@link #FOREIGN_IMAGES} 's order.
     *
     * Only {@code OUTDATED}. {@link ImageResult} keeps "nobody could look" apart from "up to date", and a registry
     * that did not answer is never a reason to recreate a container - the same rule {@link #withImages} follows.
     */
    static List<String> staleForeign(final ImageResult images) {
        return FOREIGN_IMAGES.stream().filter(images::isOutdated).toList();
    }

    /**
     * Pulls and recreates each of them, then waits for it to be healthy again.
     *
     * Last, after the Minecraft services are already back: Deliberately not folded into
     * {@link eu.nordtal.s2.steward.worker.serve.UpdateRun#start}, which renews the image of a service it has just
     * stopped. None of these three is ever stopped by this sequence: recreating them is a {@code compose up} that
     * replaces the container by itself, and none of them has a player standing on it. Putting them at the end is
     * what keeps a player from waiting on Caddy.
     *
     * What a failure here does, and what it does not: The line is FAILED and the run is FAILED with it - the caller's
     * settling reads that, and an image that could not be renewed is a run that did not do what it said. It does not
     * roll anything back: the old container is still running, which is the same outcome as never asking.
     */
    static UpdateReport renewForeign(
            final ContainerOps containers,
            final UpdateRun run,
            final UpdateReport before,
            final List<String> services,
            final Consumer<UpdateReport> progress) {
        if (services.isEmpty()) {
            return before;
        }
        UpdateReport report = before;
        final List<String> asked = new ArrayList<>();
        for (final String service : services) {
            // Written before the call: a recreate that never returns leaves this as the report's last word.
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
        // Not politeness: this process writes the run's final report through postgres after returning from here.
        return asked.isEmpty() ? report : run.verify(report, asked, UpdateRun.Waiting.real());
    }
}
