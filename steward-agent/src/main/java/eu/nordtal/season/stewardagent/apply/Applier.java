package eu.nordtal.season.stewardagent.apply;

import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.JarName;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.plan.Change;
import eu.nordtal.season.stewardagent.plan.Installation;
import eu.nordtal.season.stewardagent.plan.PluginsApi;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import eu.nordtal.season.stewardagent.source.Fetcher;
import eu.nordtal.season.stewardagent.source.RemoteFile;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Turns an {@link UpdatePlan} into files on disk, one whole service at a time.
 *
 * Downloads are staged on the same filesystem and moved atomically once every artefact of a service is present.
 */
@Slf4j
public final class Applier {

    public static final String STAGING = ".nordtal-staging";

    private final RunSpec config;
    private final Fetcher fetcher;

    private final AgentWire.Topology topology;

    public Applier(
            final RunSpec config,
            final Fetcher fetcher,
            final AgentWire.Topology topology) {
        this.config = config;
        this.fetcher = fetcher;
        this.topology = topology;
    }

    public ApplyResult apply(final UpdatePlan plan) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();
        final Path root = Path.of(config.volumesRoot());

        // A service moves together or not at all.
        final Map<String, List<Change>> byService = new LinkedHashMap<>();
        for (final Change change : plan.changes()) {
            if (change.service() != null) {
                byService
                        .computeIfAbsent(change.service(), key -> new ArrayList<>())
                        .add(change);
            }
        }

        for (final Map.Entry<String, List<Change>> entry : byService.entrySet()) {
            final List<UpdatePlan.Retiring> retiring = plan.retiring().stream()
                    .filter(gone -> gone.service().equals(entry.getKey()))
                    .toList();
            outcomes.addAll(applyService(root, entry.getKey(), entry.getValue(), retiring));
        }

        outcomes.addAll(fillStandbys(root, byService.keySet()));

        return new ApplyResult(List.copyOf(outcomes));
    }

    /** Fills the standby volumes, last and always. */
    private List<ApplyResult.Outcome> fillStandbys(final Path root, final Set<String> services) {
        return Standbys.fill(root, services, topology);
    }

    private List<ApplyResult.Outcome> applyService(
            final Path root,
            final String service,
            final List<Change> changes,
            final List<UpdatePlan.Retiring> retiring) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();

        final Change blocked = UpdatePlan.blocker(changes);
        if (blocked != null) {
            markServiceBlocked(service, changes, blocked, outcomes);
            return outcomes;
        }

        markUnresolvedServerJarsSkipped(service, changes, outcomes);

        final List<Change> work = changes.stream()
                .filter(change -> change.status().isWork())
                .filter(change -> change.wanted() != null)
                .toList();

        markUnsupportedOrUnchanged(service, changes, outcomes);
        final Path volume = root.resolve(service);
        retiring.forEach(gone -> outcomes.add(retire(volume, gone)));

        if (work.isEmpty()) {
            return outcomes;
        }

        final Map<Path, Path> stagingByDestination = new LinkedHashMap<>();
        final Map<String, Path> staged;
        try {
            staged = stageWork(volume, work, stagingByDestination);
        } catch (final IOException failed) {
            log.warn("staging failed: service={} cause={}", service, failed.getMessage());
            final String why = "download failed (" + failed.getMessage() + "); nothing on this server was moved";
            work.forEach(change ->
                    outcomes.add(new ApplyResult.Outcome(service, change.artifact(), ApplyResult.Status.FAILED, why)));
            stagingByDestination.values().forEach(Applier::quietlyDelete);
            return outcomes;
        }

        outcomes.addAll(moveIntoPlace(service, volume, work, staged));
        stagingByDestination.values().forEach(Applier::quietlyDelete);
        return outcomes;
    }

    private static void markServiceBlocked(
            final String service,
            final List<Change> changes,
            final Change blocked,
            final List<ApplyResult.Outcome> outcomes) {
        final String why =
                blocked.artifact() + " could not be checked" + why(blocked) + ", so nothing on this server was touched";
        changes.forEach(change -> outcomes.add(
                        new ApplyResult.Outcome(service, change.artifact(), ApplyResult.Status.SKIPPED, why)));
    }

    private static void markUnresolvedServerJarsSkipped(
            final String service, final List<Change> changes, final List<ApplyResult.Outcome> outcomes) {
        // A server jar that could not be resolved does not block the plugins, which compile against the version.
        changes.stream()
                .filter(change -> change.status().isFailure())
                .filter(change -> isServerJar(change.artifact()))
                .forEach(change -> outcomes.add(new ApplyResult.Outcome(
                        service,
                        change.artifact(),
                        ApplyResult.Status.SKIPPED,
                        "could not be checked" + why(change)
                                + "; the build in .server/ stays, and the plugins were not held back for it")));
    }

    private static void markUnsupportedOrUnchanged(
            final String service, final List<Change> changes, final List<ApplyResult.Outcome> outcomes) {
        changes.stream()
                .filter(change -> !change.status().isWork())
                .filter(change -> !change.status().isFailure())
                .forEach(change -> outcomes.add(
                        change.status() == Change.Status.UNSUPPORTED
                                // Not UNCHANGED: nothing is there and nothing was attempted.
                                ? new ApplyResult.Outcome(
                                        service,
                                        change.artifact(),
                                        ApplyResult.Status.UNSUPPORTED,
                                        "no build for this Minecraft version yet")
                                : new ApplyResult.Outcome(
                                        service, change.artifact(), ApplyResult.Status.UNCHANGED, change.installed())));
    }

    private Map<String, Path> stageWork(final Path volume, final List<Change> work, final Map<Path, Path> byDestination)
            throws IOException {
        final Map<String, Path> staged = new LinkedHashMap<>();
        for (final Change change : work) {
            final RemoteFile wanted = Objects.requireNonNull(change.wanted(), "work is filtered to wanted() != null");
            final Path destination = directoryFor(volume, change.artifact());
            final Path staging = stagingFor(byDestination, destination);
            final Path target = staging.resolve(wanted.fileName());
            fetcher.fetch(wanted, target);
            staged.put(change.artifact(), target);
        }
        return staged;
    }

    private List<ApplyResult.Outcome> moveIntoPlace(
            final String service, final Path volume, final List<Change> work, final Map<String, Path> staged) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();
        for (final Change change : work) {
            final RemoteFile wanted = Objects.requireNonNull(change.wanted(), "work is filtered to wanted() != null");
            final Path destination = directoryFor(volume, change.artifact()).resolve(wanted.fileName());
            final Path destinationDirectory =
                    Objects.requireNonNull(destination.getParent(), "resolved against a directory");
            try {
                Files.createDirectories(destinationDirectory);
                // ATOMIC_MOVE only: a silent copy fallback could leave a half-written jar.
                Files.move(
                        staged.get(change.artifact()),
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                final List<String> removed = removeSuperseded(destinationDirectory, wanted.fileName());
                outcomes.add(new ApplyResult.Outcome(
                        service,
                        change.artifact(),
                        ApplyResult.Status.DONE,
                        describe(change, wanted, removed),
                        wanted.fileName()));
            } catch (final IOException failed) {
                // This is the one case in which a server can be left mixed.
                outcomes.add(new ApplyResult.Outcome(
                        service,
                        change.artifact(),
                        ApplyResult.Status.FAILED,
                        "could not move " + wanted.fileName() + " into place: " + failed.getMessage()
                                + ". This server may now be part-updated - check it before restarting."));
            }
        }
        return outcomes;
    }

    /** Returns the staging directory of one destination, created on first use and emptied first. */
    private static Path stagingFor(final Map<Path, Path> known, final Path destination) throws IOException {
        final Path existing = known.get(destination);
        if (existing != null) {
            return existing;
        }
        final Path staging = destination.resolve(STAGING);
        deleteRecursively(staging);
        Files.createDirectories(staging);
        known.put(destination, staging);
        return staging;
    }

    private static Path directoryFor(final Path volume, final String artifact) {
        return isServerJar(artifact) ? volume.resolve(Installation.SERVER_CACHE) : volume.resolve(Installation.PLUGINS);
    }

    /** Deletes a retired plugin's jars and data folder; a failure names the file it stopped at. */
    private static ApplyResult.Outcome retire(final Path volume, final UpdatePlan.Retiring gone) {
        final Path plugins = volume.resolve(Installation.PLUGINS);
        final List<String> removed = new ArrayList<>();
        for (final String name : gone.files()) {
            try {
                PluginsApi.deleteTree(plugins.resolve(name));
                removed.add(name);
            } catch (final IOException failed) {
                return new ApplyResult.Outcome(
                        gone.service(),
                        gone.artifact(),
                        ApplyResult.Status.FAILED,
                        "could not remove " + name + ": " + failed.getMessage());
            }
        }
        return new ApplyResult.Outcome(
                gone.service(), gone.artifact(), ApplyResult.Status.DONE, "removed " + String.join(", ", removed));
    }

    private static boolean isServerJar(final String artifact) {
        return Topology.PAPER.equals(artifact) || Topology.VELOCITY.equals(artifact);
    }

    private static List<String> removeSuperseded(final Path directory, final String installed) throws IOException {
        final List<String> removed = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && JarName.looksSuperseded(name, installed)) {
                    Files.delete(entry);
                    removed.add(name);
                }
            }
        }
        return removed;
    }

    private static String describe(final Change change, final RemoteFile wanted, final List<String> removed) {
        final StringBuilder detail = new StringBuilder();
        if (change.installed() != null) {
            detail.append(change.installed()).append(" -> ");
        }
        detail.append(wanted.fileName());
        if (!removed.isEmpty()) {
            detail.append(" (removed ").append(String.join(", ", removed)).append(')');
        }
        return detail.toString();
    }

    private static void deleteRecursively(final @Nullable Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(directory);
    }

    private static void quietlyDelete(final Path directory) {
        try {
            deleteRecursively(directory);
        } catch (final IOException leftBehind) {
            // The jars are in place, and the next run empties the directory before use.
            log.warn("directory not cleaned up: dir={} cause={}", directory, leftBehind.getMessage());
        }
    }

    private static String why(final Change change) {
        final MessageRef reason = change.reason();
        return reason == null ? "" : " (" + DatabaseText.english(reason) + ")";
    }
}
