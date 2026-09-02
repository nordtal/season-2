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
 * Step 3 of docs/updater.md: turn an {@link UpdatePlan} into files on disk.
 *
 * <h2>Two phases, and that is the whole design</h2>
 * Everything a server needs is downloaded into a staging directory <em>inside that server's own
 * volume</em> and verified there. Only when every one of them is present does anything move into
 * {@code plugins/} or {@code .server/}. A download that fails half way through leaves a staging
 * directory nobody will look at and a server exactly as it was.
 *
 * <p>{@code entrypoint.sh} does not work this way - it fetches and places one jar at a time - and
 * the difference matters here for a reason it did not there: this module moves <b>eight</b>
 * artefacts across four servers in one go, and a network running four servers on two versions of
 * the season is a worse state than a network that did not update.</p>
 *
 * <p>The staging directory has to be in the same volume, not in {@code /tmp}: a rename across a
 * mount boundary is a copy, and a copy is not atomic.</p>
 *
 * <h2>A server moves together or not at all</h2>
 * If any artefact of a service could not be resolved, that whole service is skipped. "The new SMP
 * jar with last week's PacketEvents" is a combination nobody chose and nobody tested, and
 * DisplayTags is a <em>required</em> plugin of {@code smp} whose own required plugin PacketEvents
 * is - so a partial swap there is a server that does not start.
 *
 * <p>The server jar is the one exception, since 2026-09-02: a Paper or Velocity build that could
 * not be resolved is reported as skipped on its own row and the plugins move anyway. Plugins are
 * compiled against the version, not the build, and the build already in {@code .server/} runs.</p>
 *
 * <h2>What is deleted</h2>
 * Only a jar in the target directory whose filename prefix matches the one just installed, and
 * only after the new jar is safely in place - the same rule {@code entrypoint.sh} has always used
 * ({@link JarName}). A jar nothing accounts for is never touched; it is reported and left alone.
 */
@Slf4j
public final class Applier {

    /**
     * Where downloads land before they are moved. A dot directory inside the volume: same
     * filesystem as the destination, and invisible to anybody listing {@code plugins/}.
     */
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

        // Nothing is left over: since 2026-09-01 the bot and the updater are services in this map
        // too, each with one artefact and a volume whose root is where its jar goes.
        return new ApplyResult(List.copyOf(outcomes));
    }

    // ---------------------------------------------------------------- one service

    private List<ApplyResult.Outcome> applyService(final Path root, final String service,
                                                    final List<Change> changes) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();

        final Change blocked = changes.stream()
                .filter(change -> change.status().isFailure())
                .filter(change -> !isServerJar(change.artifact()))
                .findFirst()
                .orElse(null);
        if (blocked != null) {
            final String why = blocked.artifact() + " could not be checked"
                    + (blocked.note() == null ? "" : " (" + blocked.note() + ")")
                    + ", so nothing on this server was touched";
            changes.forEach(change -> outcomes.add(new ApplyResult.Outcome(
                    service, change.artifact(), ApplyResult.Status.SKIPPED, why)));
            return outcomes;
        }

        // A server jar that could not be resolved does NOT block the plugins beside it (decided
        // 2026-09-02). The all-or-nothing rule exists because DisplayTags is a required plugin of
        // smp and PacketEvents is required under it - a partial swap there is a server that does
        // not start. The server jar is outside that coupling: every plugin is compiled against the
        // VERSION, which never moves here, and never against a build; and the build lying in
        // .server/ is one that already runs. Blocking three servers' plugins because Fill was down
        // for a minute would produce exactly the split network the rule is meant to prevent, with
        // the bot and the updater - which have no Fill row - moving on regardless.
        changes.stream()
                .filter(change -> change.status().isFailure())
                .filter(change -> isServerJar(change.artifact()))
                .forEach(change -> outcomes.add(new ApplyResult.Outcome(
                        service, change.artifact(), ApplyResult.Status.SKIPPED,
                        "could not be checked" + (change.note() == null ? "" : " (" + change.note() + ")")
                                + "; the build in .server/ stays, and the plugins were not held back for it")));

        // The pack is NOT a file this module downloads. The proxy only describes it - url and
        // sha1 - and the Minecraft client fetches the zip itself. Putting a 40 KB pack zip into a
        // plugins folder would be harmless and completely pointless, and it is excluded here
        // rather than special-cased three lines further down. applyPack handles it.
        final List<Change> work = changes.stream()
                .filter(change -> change.status().isWork())
                .filter(change -> change.wanted() != null)
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .toList();

        changes.stream()
                .filter(change -> !change.status().isWork())
                .filter(change -> !change.status().isFailure())
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .forEach(change -> outcomes.add(new ApplyResult.Outcome(
                        service, change.artifact(), ApplyResult.Status.UNCHANGED, change.installed())));

        if (work.isEmpty()) {
            outcomes.addAll(applyPack(root, service, changes));
            return outcomes;
        }

        final Path volume = root.resolve(service);
        final Path staging = volume.resolve(STAGING);

        // --- phase one: fetch everything, place nothing -----------------------------------
        final Map<String, Path> staged = new LinkedHashMap<>();
        try {
            deleteRecursively(staging);
            Files.createDirectories(staging);
            for (final Change change : work) {
                final RemoteFile wanted = change.wanted();
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
            quietlyDelete(staging);
            outcomes.addAll(applyPack(root, service, changes));
            return outcomes;
        }

        // --- phase two: move them all in ---------------------------------------------------
        for (final Change change : work) {
            final RemoteFile wanted = change.wanted();
            final Path destination = directoryFor(volume, change.artifact()).resolve(wanted.fileName());
            try {
                Files.createDirectories(destination.getParent());
                Files.move(staged.get(change.artifact()), destination,
                        StandardCopyOption.REPLACE_EXISTING);
                final List<String> removed = removeSuperseded(destination.getParent(), wanted.fileName());
                outcomes.add(new ApplyResult.Outcome(service, change.artifact(), ApplyResult.Status.DONE,
                        describe(change, wanted, removed)));
            } catch (final IOException failed) {
                // Phase two failing is a filesystem problem, not a network one, and it is the one
                // case where a server can be left mixed. Said plainly rather than smoothed over.
                outcomes.add(new ApplyResult.Outcome(service, change.artifact(), ApplyResult.Status.FAILED,
                        "could not move " + wanted.fileName() + " into place: " + failed.getMessage()
                                + ". This server may now be part-updated - check it before restarting."));
            }
        }

        quietlyDelete(staging);
        outcomes.addAll(applyPack(root, service, changes));
        return outcomes;
    }

    // ---------------------------------------------------------------- the pack

    /**
     * The proxy's {@code pack.yml}, written after its jars. The two values come from the release
     * itself: the URL is the asset's own {@code github.com/.../releases/download/...} address, and
     * the SHA-1 is the content of the {@code .sha1} asset beside the zip - never computed, never
     * copied by a person.
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
     * Server jars live in the entrypoint's cache, the bot and the updater are the volume's whole
     * contents, and everything else is a plugin.
     *
     * <p><b>The updater deleting its own superseded jar while running from it is safe, and only on
     * Linux.</b> Unlinking an open file leaves the inode alive for whoever holds it, so the JVM
     * keeps reading classes out of a jar that no longer has a name. On Windows the delete would
     * fail outright. This only ever runs in a container, so the trade is one comment rather than a
     * special case - but it is a real dependency and it is written down.</p>
     */
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
            // A staging directory nobody will read is worth a line and not a failure: the jars are
            // already where they belong, and the next run empties it before using it again.
            log.warn("Could not clean up {}: {}", directory, leftBehind.getMessage());
        }
    }
}
