package eu.nordtal.s2.steward.apply;

import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.internalapi.agent.JarName;
import eu.nordtal.s2.steward.config.StewardSpec;
import eu.nordtal.s2.steward.plan.Change;
import eu.nordtal.s2.steward.plan.Installation;
import eu.nordtal.s2.steward.plan.Topology;
import eu.nordtal.s2.steward.plan.UpdatePlan;
import eu.nordtal.s2.steward.source.Checksum;
import eu.nordtal.s2.steward.source.Fetcher;
import eu.nordtal.s2.steward.source.RemoteFile;
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

    /** Dot directory inside the destination: same filesystem by construction, and nothing lists it. */
    public static final String STAGING = ".nordtal-staging";

    private final StewardSpec config;
    private final Fetcher fetcher;

    /** Where the proxy's pack is set. */
    private final SettingStore settings;

    public Applier(final StewardSpec config, final Fetcher fetcher, final SettingStore settings) {
        this.config = config;
        this.fetcher = fetcher;
        this.settings = settings;
    }

    public ApplyResult apply(final UpdatePlan plan) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();
        final Path root = Path.of(config.volumesRoot());

        // Grouped by service, since a service moves together or not at all.
        final Map<String, List<Change>> byService = new LinkedHashMap<>();
        for (final Change change : plan.changes()) {
            if (change.service() != null) {
                byService
                        .computeIfAbsent(change.service(), key -> new ArrayList<>())
                        .add(change);
            }
        }

        for (final Map.Entry<String, List<Change>> entry : byService.entrySet()) {
            outcomes.addAll(applyService(root, entry.getKey(), entry.getValue()));
        }

        outcomes.addAll(fillStandbys(root, byService.keySet()));

        return new ApplyResult(List.copyOf(outcomes));
    }

    /**
     * Fills the standby volumes, always last and always attempted.
     *
     * Last, so the copy is of finished files; always, since a standby can be empty while its service is unchanged.
     */
    private static List<ApplyResult.Outcome> fillStandbys(final Path root, final Set<String> services) {
        return Standbys.fill(root, services);
    }

    private List<ApplyResult.Outcome> applyService(final Path root, final String service, final List<Change> changes) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();

        final Change blocked = UpdatePlan.blocker(changes);
        if (blocked != null) {
            markServiceBlocked(service, changes, blocked, outcomes);
            outcomes.addAll(applyPack(service, changes));
            return outcomes;
        }

        markUnresolvedServerJarsSkipped(service, changes, outcomes);

        // The client fetches the pack zip, so applyPack handles it.
        final List<Change> work = changes.stream()
                .filter(change -> change.status().isWork())
                .filter(change -> change.wanted() != null)
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .toList();

        markUnsupportedOrUnchanged(service, changes, outcomes);

        if (work.isEmpty()) {
            outcomes.addAll(applyPack(service, changes));
            return outcomes;
        }

        final Path volume = root.resolve(service);
        final Map<Path, Path> stagingByDestination = new LinkedHashMap<>();
        final Map<String, Path> staged;
        try {
            staged = stageWork(volume, work, stagingByDestination);
        } catch (final IOException failed) {
            log.warn("Staging {} failed: {}", service, failed.getMessage());
            final String why = "download failed (" + failed.getMessage() + "); nothing on this server was moved";
            work.forEach(change ->
                    outcomes.add(new ApplyResult.Outcome(service, change.artifact(), ApplyResult.Status.FAILED, why)));
            stagingByDestination.values().forEach(Applier::quietlyDelete);
            outcomes.addAll(applyPack(service, changes));
            return outcomes;
        }

        outcomes.addAll(moveIntoPlace(service, volume, work, staged));
        stagingByDestination.values().forEach(Applier::quietlyDelete);
        outcomes.addAll(applyPack(service, changes));
        return outcomes;
    }

    private static void markServiceBlocked(
            final String service,
            final List<Change> changes,
            final Change blocked,
            final List<ApplyResult.Outcome> outcomes) {
        final String why = blocked.artifact() + " could not be checked"
                + (blocked.note() == null ? "" : " (" + blocked.note() + ")")
                + ", so nothing on this server was touched";
        // The pack still gets its own row: a skipped service must still say what the client is sent.
        changes.stream()
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .forEach(change -> outcomes.add(
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
                        "could not be checked" + (change.note() == null ? "" : " (" + change.note() + ")")
                                + "; the build in .server/ stays, and the plugins were not held back for it")));
    }

    private static void markUnsupportedOrUnchanged(
            final String service, final List<Change> changes, final List<ApplyResult.Outcome> outcomes) {
        changes.stream()
                .filter(change -> !change.status().isWork())
                .filter(change -> !change.status().isFailure())
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
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
        // Sweeps up the old layout's staging directory at the volume root.
        deleteRecursively(volume.resolve(STAGING));
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
                        service, change.artifact(), ApplyResult.Status.DONE, describe(change, wanted, removed)));
            } catch (final IOException failed) {
                // The one case where a server can be left mixed, so it is said plainly.
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

    /** The proxy's pack, set after its jars, with the URL and sha1 the release publishes. */
    private List<ApplyResult.Outcome> applyPack(final String service, final List<Change> changes) {
        final Change pack = changes.stream()
                .filter(change -> change.artifact().equals(Topology.RESOURCE_PACK))
                .findFirst()
                .orElse(null);
        if (pack == null) {
            return List.of();
        }

        // "Could not be checked" is not "unchanged": the proxy keeps the previous pack.
        if (pack.status().isFailure()) {
            return List.of(new ApplyResult.Outcome(
                    service,
                    Topology.RESOURCE_PACK,
                    ApplyResult.Status.SKIPPED,
                    "could not be checked" + (pack.note() == null ? "" : " (" + pack.note() + ")")
                            + "; the proxy's pack was left alone, so the client is still sent "
                            + (pack.installed() == null ? "whatever it already said" : pack.installed())
                            + ". The jars beside it were not held back for it."));
        }

        if (!pack.status().isWork() || pack.wanted() == null) {
            return List.of(new ApplyResult.Outcome(
                    service, Topology.RESOURCE_PACK, ApplyResult.Status.UNCHANGED, pack.installed()));
        }

        final RemoteFile wanted = pack.wanted();
        final Checksum sha1 = wanted.checksum();
        if (sha1 == null || !"sha1".equals(sha1.algorithm())) {
            return List.of(new ApplyResult.Outcome(
                    service,
                    Topology.RESOURCE_PACK,
                    ApplyResult.Status.FAILED,
                    "the release published no .sha1 for the pack; the client is sent both or neither"));
        }

        try {
            final boolean written = PackWriter.write(settings, wanted.url().toString(), sha1.hex());
            return List.of(new ApplyResult.Outcome(
                    service,
                    Topology.RESOURCE_PACK,
                    written ? ApplyResult.Status.DONE : ApplyResult.Status.UNCHANGED,
                    written
                            ? "the proxy now sends " + wanted.fileName() + " (sha1 " + sha1.hex() + ")"
                            : "the proxy already sent this"));
        } catch (final RuntimeException failed) {
            return List.of(new ApplyResult.Outcome(
                    service, Topology.RESOURCE_PACK, ApplyResult.Status.FAILED, failed.getMessage()));
        }
    }

    /**
     * The staging directory for one destination, created on first use and emptied first.
     *
     * Emptied, since leftovers from a dead run were never verified.
     */
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
        if (Topology.isStandalone(artifact)) {
            return volume;
        }
        return isServerJar(artifact) ? volume.resolve(Installation.SERVER_CACHE) : volume.resolve(Installation.PLUGINS);
    }

    private static boolean isServerJar(final String artifact) {
        return Topology.PAPER.equals(artifact) || Topology.VELOCITY.equals(artifact);
    }

    /**
     * Deletes any jar this artefact's new file supersedes.
     *
     * Deleting steward's own running jar is safe only because Linux keeps an unlinked inode alive.
     */
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
            // Not a failure: the jars are in place and the next run empties this before using it.
            log.warn("Could not clean up {}: {}", directory, leftBehind.getMessage());
        }
    }
}
