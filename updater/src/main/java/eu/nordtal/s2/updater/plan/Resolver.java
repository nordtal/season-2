package eu.nordtal.s2.updater.plan;

import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.source.Checksum;
import eu.nordtal.s2.updater.source.GitHubReleases;
import eu.nordtal.s2.updater.source.Modrinth;
import eu.nordtal.s2.updater.source.PaperFill;
import eu.nordtal.s2.updater.source.RemoteFile;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 1 of docs/updater.md: ask every source what is newest, look at what is on disk, and say
 * what the difference is. <b>Nothing here writes anything, anywhere.</b>
 *
 * <h2>One failure does not cost the whole report</h2>
 * Each source is asked inside its own try. A Modrinth outage turns two rows into
 * {@link Change.Status#UNRESOLVED} and leaves the other eight answered - because the question an
 * operator is actually asking is usually about our own jars, and losing that answer to somebody
 * else's CDN would make the report worth less than the {@code .env} file it replaces.
 *
 * <p>What is <em>not</em> done is the opposite mistake: an unreachable source never reads as
 * "unchanged". {@link UpdatePlan#hasFailures()} exists so that "nothing to do" can be distinguished
 * from "nothing could be asked", and the restart button in step 4 is meant to look different in
 * those two cases.</p>
 */
@Slf4j
public final class Resolver {

    private final UpdaterSpec config;
    private final GitHubReleases github;
    private final Modrinth modrinth;
    private final PaperFill fill;
    private final Clock clock;

    public Resolver(final UpdaterSpec config, final GitHubReleases github, final Modrinth modrinth,
                    final PaperFill fill, final Clock clock) {
        this.config = config;
        this.github = github;
        this.modrinth = modrinth;
        this.fill = fill;
        this.clock = clock;
    }

    public @NotNull UpdatePlan resolve() {
        final Map<String, RemoteFile> newest = new LinkedHashMap<>();
        final Map<String, String> failures = new HashMap<>();

        final GitHubReleases.Release season = resolveSeason(newest, failures);
        resolveDisplayTags(newest, failures);
        resolveModrinth(newest, failures, Topology.PACKETEVENTS, config.packetEventsProject());
        resolveModrinth(newest, failures, Topology.CHUNKY, config.chunkyProject());
        resolveFill(newest, failures, Topology.PAPER, config.minecraftVersion());
        resolveFill(newest, failures, Topology.VELOCITY, config.velocityVersion());

        final List<Change> changes = new ArrayList<>();
        final List<UpdatePlan.Unclaimed> unclaimed = new ArrayList<>();
        final Path root = Path.of(config.volumesRoot());

        for (final Topology.Service service : Topology.SERVICES) {
            final Installation installed = scan(service.name(), root.resolve(service.name()));

            // Every jar the topology accounts for on this service, by filename prefix. What is left
            // over at the end is what nothing claims.
            final Set<String> claimed = new HashSet<>();

            final List<String> artifacts = new ArrayList<>(service.plugins());
            artifacts.add(service.kind().fillProject());

            for (final String artifact : artifacts) {
                changes.add(compare(service.name(), artifact, installed, newest, failures, claimed));
            }

            if (installed.mounted()) {
                for (final Installation.Jar jar : installed.plugins()) {
                    if (jar.prefix() != null && !claimed.contains(jar.prefix())) {
                        unclaimed.add(new UpdatePlan.Unclaimed(service.name(), jar.fileName()));
                    }
                }
            }
        }

        changes.add(resolveBot(newest, failures));
        changes.add(resolveSelf(newest, failures));
        changes.add(resolvePack(root, newest, failures));

        return new UpdatePlan(
                clock.instant(),
                season == null ? null : season.tag(),
                season != null && season.prerelease(),
                List.copyOf(changes),
                List.copyOf(unclaimed));
    }

    // ---------------------------------------------------------------- sources

    private @Nullable GitHubReleases.Release resolveSeason(final Map<String, RemoteFile> newest,
                                                           final Map<String, String> failures) {
        final GitHubReleases.Release release;
        try {
            release = github.fetch(config.seasonRepo(), config.seasonRelease());
        } catch (final IOException failed) {
            // Our own six jars and the pack all come from this one call, so this is the failure
            // that costs the most - named as one reason on every row rather than repeated on each.
            final String why = "could not read " + config.seasonRepo() + "@" + config.seasonRelease()
                    + ": " + failed.getMessage();
            log.warn("Season release unresolved - {}", why);
            Topology.SEASON_JARS.forEach(artifact -> failures.put(artifact, why));
            failures.put(Topology.RESOURCE_PACK, why);
            return null;
        }

        for (final GitHubReleases.Asset asset : release.assets()) {
            final String prefix = JarName.prefixOf(asset.name());
            // The asset's own prefix is the artifact id for all five of our jars - smp-0.2.0.jar
            // is 'smp'. Matching that way rather than by a built name means a release carrying an
            // extra asset is ignored instead of being a parse error.
            if (prefix != null && Topology.SEASON_JARS.contains(prefix)) {
                newest.put(prefix, new RemoteFile(prefix, versionOrTag(asset.name(), release.tag()),
                        asset.name(), asset.url(), null));
            }
        }

        for (final String artifact : Topology.SEASON_JARS) {
            if (!newest.containsKey(artifact)) {
                failures.put(artifact, "release " + release.tag() + " carries no " + artifact + "-<version>.jar");
            }
        }

        resolvePackAsset(release, newest, failures);
        return release;
    }

    /**
     * The pack zip and the SHA-1 sitting next to it. The hash is <em>read</em>, not computed: it
     * is a 41-byte asset the release workflow writes, and the Minecraft client checks it against
     * the zip itself.
     */
    private void resolvePackAsset(final GitHubReleases.Release release, final Map<String, RemoteFile> newest,
                                  final Map<String, String> failures) {
        GitHubReleases.Asset zip = null;
        GitHubReleases.Asset sha1 = null;
        for (final GitHubReleases.Asset asset : release.assets()) {
            if (asset.name().endsWith(".zip.sha1")) {
                sha1 = asset;
            } else if (asset.name().endsWith(".zip")) {
                zip = asset;
            }
        }

        if (zip == null) {
            failures.put(Topology.RESOURCE_PACK, "release " + release.tag() + " carries no pack zip");
            return;
        }
        if (sha1 == null) {
            // Refused rather than worked around. The client is sent the URL and the hash together
            // and rejects a pack whose hash disagrees; offering a pack with no hash is not a
            // degraded mode, it is a different thing that does not work.
            failures.put(Topology.RESOURCE_PACK, "release " + release.tag() + " carries " + zip.name()
                    + " but no " + zip.name() + ".sha1 next to it - the client is sent both or neither");
            return;
        }

        try {
            newest.put(Topology.RESOURCE_PACK, new RemoteFile(Topology.RESOURCE_PACK,
                    versionOrTag(zip.name(), release.tag()), zip.name(), zip.url(),
                    Checksum.sha1(github.readText(sha1))));
        } catch (final IOException failed) {
            failures.put(Topology.RESOURCE_PACK, "could not read " + sha1.name() + ": " + failed.getMessage());
        }
    }

    private void resolveDisplayTags(final Map<String, RemoteFile> newest, final Map<String, String> failures) {
        try {
            final GitHubReleases.Release release =
                    github.fetch(config.displayTagsRepo(), config.displayTagsRelease());
            GitHubReleases.Asset jar = null;
            for (final GitHubReleases.Asset asset : release.assets()) {
                if (JarName.isJar(asset.name()) && !asset.name().endsWith("-sources.jar")) {
                    jar = asset;
                    break;
                }
            }
            if (jar == null) {
                failures.put(Topology.DISPLAY_TAGS, "release " + release.tag() + " carries no jar");
                return;
            }
            newest.put(Topology.DISPLAY_TAGS, new RemoteFile(Topology.DISPLAY_TAGS,
                    versionOrTag(jar.name(), release.tag()), jar.name(), jar.url(), null));
        } catch (final IOException failed) {
            failures.put(Topology.DISPLAY_TAGS, "could not read " + config.displayTagsRepo()
                    + "@" + config.displayTagsRelease() + ": " + failed.getMessage());
        }
    }

    private void resolveModrinth(final Map<String, RemoteFile> newest, final Map<String, String> failures,
                                 final String artifact, final String projectId) {
        try {
            newest.put(artifact, modrinth.newest(artifact, projectId, config.minecraftVersion(), "paper"));
        } catch (final IOException failed) {
            failures.put(artifact, failed.getMessage());
        }
    }

    private void resolveFill(final Map<String, RemoteFile> newest, final Map<String, String> failures,
                             final String project, final String version) {
        try {
            newest.put(project, fill.newestStable(project, version));
        } catch (final IOException failed) {
            failures.put(project, failed.getMessage());
        }
    }

    // ---------------------------------------------------------------- comparison

    private Change compare(final String service, final String artifact, final Installation installed,
                           final Map<String, RemoteFile> newest, final Map<String, String> failures,
                           final Set<String> claimed) {
        final RemoteFile wanted = newest.get(artifact);
        if (wanted == null) {
            return Change.unresolved(service, artifact,
                    failures.getOrDefault(artifact, "no source answered for this artefact"));
        }

        final String prefix = JarName.prefixOf(wanted.fileName());
        if (prefix != null) {
            claimed.add(prefix);
        }

        if (!installed.mounted()) {
            return new Change(service, artifact, Change.Status.MOUNT_MISSING, null, wanted,
                    installed.directory() + " is not mounted in this container");
        }

        final Installation.Jar present = installed.matching(wanted.fileName());
        if (present == null) {
            return new Change(service, artifact, Change.Status.MISSING, null, wanted, null);
        }
        if (present.fileName().equals(wanted.fileName())) {
            return new Change(service, artifact, Change.Status.UP_TO_DATE, present.fileName(), wanted, null);
        }
        return new Change(service, artifact, Change.Status.OUTDATED, present.fileName(), wanted, null);
    }

    private Change resolveBot(final Map<String, RemoteFile> newest, final Map<String, String> failures) {
        final RemoteFile wanted = newest.get(Topology.DISCORD_BOT);
        if (wanted == null) {
            return Change.unresolved(null, Topology.DISCORD_BOT,
                    failures.getOrDefault(Topology.DISCORD_BOT, "no source answered for this artefact"));
        }
        if (config.botVolume().isBlank()) {
            return new Change(null, Topology.DISCORD_BOT, Change.Status.MOUNT_MISSING, null, wanted,
                    "the bot still runs as a GHCR image, so there is no jar to compare against."
                            + " Step 3 of docs/updater.md turns it into a jar; set bot-volume then.");
        }

        final Installation installed = scan(Topology.DISCORD_BOT, Path.of(config.botVolume()));
        return compare(null, Topology.DISCORD_BOT, installed, newest, failures, new HashSet<>());
    }

    /**
     * The updater's own jar. Reported and never acted on: no process swaps its own jar and keeps
     * going, so this row exists to tell a person that a restart will bring a different updater
     * back - which is the only way this module's version ever moves.
     */
    private Change resolveSelf(final Map<String, RemoteFile> newest, final Map<String, String> failures) {
        final RemoteFile wanted = newest.get(Topology.UPDATER);
        if (wanted == null) {
            return Change.unresolved(null, Topology.UPDATER,
                    failures.getOrDefault(Topology.UPDATER, "no source answered for this artefact"));
        }
        return new Change(null, Topology.UPDATER, Change.Status.MOUNT_MISSING, null, wanted,
                "this container runs the jar baked into its image, so there is nothing to compare"
                        + " against. A new updater arrives with the restart, never during a run.");
    }

    private Change resolvePack(final Path root, final Map<String, RemoteFile> newest,
                               final Map<String, String> failures) {
        final RemoteFile wanted = newest.get(Topology.RESOURCE_PACK);
        if (wanted == null) {
            return Change.unresolved(Topology.NETWORK_CONTROL, Topology.RESOURCE_PACK,
                    failures.getOrDefault(Topology.RESOURCE_PACK, "no source answered for the pack"));
        }

        final PackState state;
        try {
            state = PackState.read(root.resolve(Topology.NETWORK_CONTROL));
        } catch (final IOException failed) {
            return Change.unresolved(Topology.NETWORK_CONTROL, Topology.RESOURCE_PACK,
                    "could not read pack.yml: " + failed.getMessage());
        }

        if (!state.present() || state.sha1() == null) {
            return new Change(Topology.NETWORK_CONTROL, Topology.RESOURCE_PACK, Change.Status.MISSING,
                    null, wanted, state.present()
                            ? "pack.yml has no sha1"
                            : PackState.fileIn(root.resolve(Topology.NETWORK_CONTROL))
                                    + " does not exist yet");
        }

        // The hash is the identity, not the URL: two releases can serve the same bytes and the
        // client keys its cache on the hash. Compared case-insensitively because a hash typed by a
        // person is the case that ever differs, and Checksum has already lowercased ours.
        final Checksum checksum = wanted.checksum();
        final String wantedSha1 = checksum == null ? null : checksum.hex();
        if (wantedSha1 != null && wantedSha1.equalsIgnoreCase(state.sha1())) {
            return new Change(Topology.NETWORK_CONTROL, Topology.RESOURCE_PACK, Change.Status.UP_TO_DATE,
                    state.sha1(), wanted, null);
        }
        return new Change(Topology.NETWORK_CONTROL, Topology.RESOURCE_PACK, Change.Status.OUTDATED,
                state.sha1(), wanted, null);
    }

    // ---------------------------------------------------------------- helpers

    private Installation scan(final String service, final Path directory) {
        try {
            return Installation.scan(service, directory);
        } catch (final IOException failed) {
            // A directory that exists but cannot be listed is a permissions problem on the mount,
            // and it must not read as an empty server.
            log.warn("Could not read {} for {}: {}", directory, service, failed.getMessage());
            return Installation.absent(service, directory);
        }
    }

    /** The version out of the filename, falling back to the tag when the name carries none. */
    private static String versionOrTag(final String fileName, final String tag) {
        final String version = JarName.versionOf(fileName);
        if (version != null) {
            return version;
        }
        // A zip, not a jar: nordtal-resource-pack-0.2.0.zip. Same rule, applied by hand.
        final int dot = fileName.lastIndexOf('.');
        final String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        final int dash = stem.lastIndexOf('-');
        return dash <= 0 ? tag : stem.substring(dash + 1);
    }
}
