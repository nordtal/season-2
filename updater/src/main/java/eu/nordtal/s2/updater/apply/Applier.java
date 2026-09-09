package eu.nordtal.s2.updater.apply;

import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.http.Fetcher;
import eu.nordtal.s2.updater.plan.Change;
import eu.nordtal.s2.updater.plan.Installation;
import eu.nordtal.s2.updater.plan.JarName;
import eu.nordtal.s2.updater.plan.PackState;
import eu.nordtal.s2.updater.plan.Topology;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.source.Checksum;
import eu.nordtal.s2.updater.source.RemoteFile;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an {@link UpdatePlan} into files on disk.
 *
 * <p>Everything is downloaded into a staging directory first and only moved into place once every
 * artefact of a service is present, so a run that fails half way leaves the server as it was. The
 * staging directory is resolved <em>per destination directory</em> because it has to sit on the
 * same filesystem: a cross-device move silently degrades to copy-and-delete, which is precisely
 * the half-written jar in {@code plugins/} this class exists to prevent.</p>
 *
 * <p>A service moves together or not at all - a partial swap of coupled plugins is a server that
 * does not start. Two artefacts are exempt. A server jar: plugins are compiled against the
 * version, never the build, and the build already in {@code .server/} runs. The resource pack: it
 * is not a file in a volume at all, and leaving {@code pack.yml} alone keeps the previous URL and
 * hash in force, which is a pack that works.</p>
 *
 * <p>Only a jar whose filename prefix matches the one just installed is deleted, and only after
 * the new jar is in place ({@link JarName}). A jar nothing accounts for is reported and left.</p>
 */
@Slf4j
public final class Applier {

    /** Dot directory inside the destination: same filesystem by construction, and nothing lists it. */
    public static final String STAGING = ".nordtal-staging";

    private final UpdaterSpec config;
    private final Fetcher fetcher;

    public Applier(final UpdaterSpec config, final Fetcher fetcher) {
        this.config = config;
        this.fetcher = fetcher;
    }

    public @NotNull ApplyResult apply(final @NotNull UpdatePlan plan) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();
        final Path root = Path.of(config.volumesRoot());

        // Grouped by service so the all-or-nothing rule has something to be all-or-nothing about.
        final Map<String, List<Change>> byService = new LinkedHashMap<>();
        for (final Change change : plan.changes()) {
            if (change.service() != null) {
                byService.computeIfAbsent(change.service(), key -> new ArrayList<>()).add(change);
            }
        }

        for (final Map.Entry<String, List<Change>> entry : byService.entrySet()) {
            outcomes.addAll(applyService(root, entry.getKey(), entry.getValue()));
        }

        return new ApplyResult(List.copyOf(outcomes));
    }

    // ---------------------------------------------------------------- one service

    private List<ApplyResult.Outcome> applyService(final Path root, final String service,
                                                    final List<Change> changes) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();

        final Change blocked = changes.stream()
                .filter(change -> change.status().isFailure())
                .filter(change -> !isServerJar(change.artifact()))
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .findFirst()
                .orElse(null);
        if (blocked != null) {
            final String why = blocked.artifact() + " could not be checked"
                    + (blocked.note() == null ? "" : " (" + blocked.note() + ")")
                    + ", so nothing on this server was touched";
            changes.stream()
                    .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                    .forEach(change -> outcomes.add(new ApplyResult.Outcome(
                            service, change.artifact(), ApplyResult.Status.SKIPPED, why)));
            // The pack still gets its own row: a skipped service must still say what the client is sent.
            outcomes.addAll(applyPack(root, service, changes));
            return outcomes;
        }

        // A server jar that could not be resolved does not block the plugins beside it: plugins are
        // compiled against the version, never the build, and the build in .server/ already runs.
        changes.stream()
                .filter(change -> change.status().isFailure())
                .filter(change -> isServerJar(change.artifact()))
                .forEach(change -> outcomes.add(new ApplyResult.Outcome(
                        service, change.artifact(), ApplyResult.Status.SKIPPED,
                        "could not be checked" + (change.note() == null ? "" : " (" + change.note() + ")")
                                + "; the build in .server/ stays, and the plugins were not held back for it")));

        // The pack is not a file this module downloads - the proxy only describes it and the client
        // fetches the zip - so it is excluded here and handled by applyPack.
        final List<Change> work = changes.stream()
                .filter(change -> change.status().isWork())
                .filter(change -> change.wanted() != null)
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .toList();

        changes.stream()
                .filter(change -> !change.status().isWork())
                .filter(change -> !change.status().isFailure())
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .forEach(change -> outcomes.add(change.status() == Change.Status.UNSUPPORTED
                        // Not UNCHANGED: nothing is there and nothing was attempted. The row keeps the
                        // artefact named until its publisher ships a build for this Minecraft version.
                        ? new ApplyResult.Outcome(service, change.artifact(),
                                ApplyResult.Status.UNSUPPORTED,
                                "no build for this Minecraft version yet")
                        : new ApplyResult.Outcome(service, change.artifact(),
                                ApplyResult.Status.UNCHANGED, change.installed())));

        if (work.isEmpty()) {
            outcomes.addAll(applyPack(root, service, changes));
            return outcomes;
        }

        final Path volume = root.resolve(service);

        // One staging directory per destination directory, built as the work is walked so an empty
        // one is never created.
        final Map<Path, Path> stagingByDestination = new LinkedHashMap<>();

        // --- phase one: fetch everything, place nothing -----------------------------------
        final Map<String, Path> staged = new LinkedHashMap<>();
        try {
            // Sweep up the old layout's staging directory at the volume root; nothing else reads it.
            deleteRecursively(volume.resolve(STAGING));
            for (final Change change : work) {
                final RemoteFile wanted = change.wanted();
                final Path destination = directoryFor(volume, change.artifact());
                final Path staging = stagingFor(stagingByDestination, destination);
                final Path target = staging.resolve(wanted.fileName());
                fetcher.fetch(wanted, target);
                staged.put(change.artifact(), target);
            }
        } catch (final IOException failed) {
            log.warn("Staging {} failed: {}", service, failed.getMessage());
            final String why = "download failed (" + failed.getMessage()
                    + "); nothing on this server was moved";
            work.forEach(change -> outcomes.add(new ApplyResult.Outcome(
                    service, change.artifact(), ApplyResult.Status.FAILED, why)));
            stagingByDestination.values().forEach(Applier::quietlyDelete);
            outcomes.addAll(applyPack(root, service, changes));
            return outcomes;
        }

        // --- phase two: move them all in ---------------------------------------------------
        for (final Change change : work) {
            final RemoteFile wanted = change.wanted();
            final Path destination = directoryFor(volume, change.artifact()).resolve(wanted.fileName());
            try {
                Files.createDirectories(destination.getParent());
                // ATOMIC_MOVE, and a failure if the filesystem cannot do one: without it a move
                // across a device boundary degrades silently to copy-and-delete. An
                // AtomicMoveNotSupportedException means staging is no longer on the same filesystem.
                Files.move(staged.get(change.artifact()), destination,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                final List<String> removed = removeSuperseded(destination.getParent(), wanted.fileName());
                outcomes.add(new ApplyResult.Outcome(service, change.artifact(), ApplyResult.Status.DONE,
                        describe(change, wanted, removed)));
            } catch (final IOException failed) {
                // The one case where a server can be left mixed, so it is said plainly.
                outcomes.add(new ApplyResult.Outcome(service, change.artifact(), ApplyResult.Status.FAILED,
                        "could not move " + wanted.fileName() + " into place: " + failed.getMessage()
                                + ". This server may now be part-updated - check it before restarting."));
            }
        }

        stagingByDestination.values().forEach(Applier::quietlyDelete);
        outcomes.addAll(applyPack(root, service, changes));
        return outcomes;
    }

    // ---------------------------------------------------------------- the pack

    /**
     * The proxy's {@code pack.yml}, written after its jars. Both values come from the release: the
     * asset's own download URL and the content of the {@code .sha1} asset beside the zip - never
     * computed here, never copied by a person.
     */
    private List<ApplyResult.Outcome> applyPack(final Path root, final String service,
                                                 final List<Change> changes) {
        final Change pack = changes.stream()
                .filter(change -> change.artifact().equals(Topology.RESOURCE_PACK))
                .findFirst()
                .orElse(null);
        if (pack == null) {
            return List.of();
        }

        // "Could not be checked" is not "unchanged": pack.yml keeps what it said, so the client is
        // still sent the previous pack. That has to read as a fallback, not as a no-op.
        if (pack.status().isFailure()) {
            return List.of(new ApplyResult.Outcome(service, Topology.RESOURCE_PACK,
                    ApplyResult.Status.SKIPPED,
                    "could not be checked" + (pack.note() == null ? "" : " (" + pack.note() + ")")
                            + "; pack.yml was left alone, so the client is still sent "
                            + (pack.installed() == null ? "whatever it already said" : pack.installed())
                            + ". The jars beside it were not held back for it."));
        }

        if (!pack.status().isWork() || pack.wanted() == null) {
            return List.of(new ApplyResult.Outcome(service, Topology.RESOURCE_PACK,
                    ApplyResult.Status.UNCHANGED, pack.installed()));
        }

        final RemoteFile wanted = pack.wanted();
        final Checksum sha1 = wanted.checksum();
        if (sha1 == null || !"sha1".equals(sha1.algorithm())) {
            return List.of(new ApplyResult.Outcome(service, Topology.RESOURCE_PACK,
                    ApplyResult.Status.FAILED,
                    "the release published no .sha1 for the pack; the client is sent both or neither"));
        }

        try {
            final boolean written = PackWriter.write(
                    PackState.fileIn(root.resolve(service)), wanted.url().toString(), sha1.hex());
            return List.of(new ApplyResult.Outcome(service, Topology.RESOURCE_PACK,
                    written ? ApplyResult.Status.DONE : ApplyResult.Status.UNCHANGED,
                    written ? "pack.yml now points at " + wanted.fileName() + " (sha1 " + sha1.hex() + ")"
                            : "pack.yml already said this"));
        } catch (final IOException failed) {
            return List.of(new ApplyResult.Outcome(service, Topology.RESOURCE_PACK,
                    ApplyResult.Status.FAILED, failed.getMessage()));
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The staging directory for one destination, created on first use and emptied first: a run that
     * died between the two phases leaves files here, and re-using them would install a jar nobody
     * verified in this run.
     */
    private static Path stagingFor(final Map<Path, Path> known, final Path destination)
            throws IOException {
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
        return isServerJar(artifact)
                ? volume.resolve(Installation.SERVER_CACHE)
                : volume.resolve(Installation.PLUGINS);
    }

    private static boolean isServerJar(final String artifact) {
        return Topology.PAPER.equals(artifact) || Topology.VELOCITY.equals(artifact);
    }

    // The updater deleting its own superseded jar while running from it is safe only because Linux
    // keeps an unlinked inode alive for whoever holds it open; on Windows the delete would fail.
    private static List<String> removeSuperseded(final Path directory, final String installed)
            throws IOException {
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

    private static String describe(final Change change, final RemoteFile wanted,
                                   final List<String> removed) {
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
