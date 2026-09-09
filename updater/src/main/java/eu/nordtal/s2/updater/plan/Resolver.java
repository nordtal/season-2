package eu.nordtal.s2.updater.plan;

import eu.nordtal.s2.common.Platform;
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
 * Asks every source what is newest, looks at what is on disk, and says what the difference is.
 * <b>Nothing here writes anything, anywhere.</b>
 *
 * <p>Each source is asked inside its own try, so one outage costs only its own rows. An unreachable
 * source never reads as "unchanged", though: {@link UpdatePlan#hasFailures()} exists so that
 * "nothing to do" and "nothing could be asked" can be told apart.</p>
 *
 * <p>"No build for this Minecraft version" is a third answer -
 * {@link Change.Status#UNSUPPORTED} - and not a failure, because a failure row makes the whole
 * service skipped: one plugin lagging behind the platform must not stop the season jar beside it
 * from ever being installed.</p>
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
        // Kept apart from `failures`: both mean "no file to install", only one means the report is
        // untrustworthy.
        final Map<String, String> unsupported = new HashMap<>();
        final List<String> notes = new ArrayList<>();

        final GitHubReleases.Release season = resolveSeason(newest, failures);
        resolveDisplayTags(newest, failures);
        resolveModrinth(newest, failures, unsupported, Topology.PACKETEVENTS, config.packetEventsProject(), "paper");
        resolveModrinth(newest, failures, unsupported, Topology.CHUNKY, config.chunkyProject(), "paper");
        resolveModrinth(newest, failures, unsupported, Topology.VOICE_CHAT, config.voiceChatProject(), "paper");
        // The same Modrinth project, asked again for its Velocity build: one id, two jars that move
        // separately, told apart by the loader.
        resolveModrinth(newest, failures, unsupported, Topology.VOICE_CHAT_PROXY, config.voiceChatProject(), "velocity");
        resolveModrinth(newest, failures, unsupported, Topology.CORE_PROTECT, config.coreProtectProject(), "paper");
        resolvePaper(newest, failures);
        resolveVelocity(newest, failures, notes);

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
                changes.add(compare(service.name(), artifact, installed, newest, failures,
                        unsupported, claimed));
            }

            if (installed.mounted()) {
                for (final Installation.Jar jar : installed.plugins()) {
                    if (jar.prefix() != null && !claimed.contains(jar.prefix())) {
                        unclaimed.add(new UpdatePlan.Unclaimed(service.name(), jar.fileName()));
                    }
                }
            }
        }

        for (final String artifact : Topology.STANDALONE_JARS) {
            changes.add(resolveStandalone(root, artifact, newest, failures));
        }
        changes.add(resolvePack(root, newest, failures));

        return new UpdatePlan(
                clock.instant(),
                season == null ? null : season.tag(),
                season != null && season.prerelease(),
                List.copyOf(changes),
                List.copyOf(unclaimed),
                List.copyOf(notes));
    }

    // ---------------------------------------------------------------- sources

    private @Nullable GitHubReleases.Release resolveSeason(final Map<String, RemoteFile> newest,
                                                           final Map<String, String> failures) {
        final GitHubReleases.Release release;
        try {
            release = github.latest(config.seasonRepo());
        } catch (final IOException failed) {
            // Our own jars and the pack all come from this one call, so one reason covers every row.
            final String why = "could not read the latest release of " + config.seasonRepo()
                    + ": " + failed.getMessage();
            log.warn("Season release unresolved - {}", why);
            Topology.SEASON_JARS.forEach(artifact -> failures.put(artifact, why));
            failures.put(Topology.RESOURCE_PACK, why);
            return null;
        }

        for (final GitHubReleases.Asset asset : release.assets()) {
            final String prefix = JarName.prefixOf(asset.name());
            // The asset's own prefix is the artifact id (smp-0.2.0.jar is 'smp'), so a release
            // carrying an extra asset is ignored rather than being a parse error.
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
            // Refused rather than worked around: the client is sent the URL and the hash together
            // and rejects a pack whose hash disagrees.
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
            final GitHubReleases.Release release = github.latest(config.displayTagsRepo());
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
            failures.put(Topology.DISPLAY_TAGS, "could not read the latest release of "
                    + config.displayTagsRepo() + ": " + failed.getMessage());
        }
    }

    /**
     * One Modrinth-hosted plugin, with the two ways of having no file kept apart.
     * {@link Modrinth.Unsupported} is not an outage and must not be reported as one: a failure row
     * makes {@code Applier} skip the whole service the plugin sits on.
     */
    private void resolveModrinth(final Map<String, RemoteFile> newest, final Map<String, String> failures,
                                 final Map<String, String> unsupported,
                                 final String artifact, final String projectId, final String loader) {
        try {
            newest.put(artifact, modrinth.newest(artifact, projectId, Platform.MINECRAFT, loader));
        } catch (final Modrinth.Unsupported none) {
            log.info("{} has no build for Minecraft {} - the row stays in the plan and installs"
                    + " itself when one appears", artifact, Platform.MINECRAFT);
            unsupported.put(artifact, none.getMessage());
        } catch (final IOException failed) {
            failures.put(artifact, failed.getMessage());
        }
    }

    /**
     * The newest stable build of {@link Platform#MINECRAFT}, which is an <em>exact</em> version and
     * not a family: a new Minecraft version is a season decision, and a Fill family also lists its
     * release candidates.
     */
    private void resolvePaper(final Map<String, RemoteFile> newest, final Map<String, String> failures) {
        try {
            newest.put(Topology.PAPER, fill.newestStable(Topology.PAPER, Platform.MINECRAFT));
        } catch (final IOException failed) {
            failures.put(Topology.PAPER, failed.getMessage());
        }
    }

    /**
     * The newest stable build inside {@link Platform#VELOCITY_FAMILY}, so the proxy follows
     * Velocity's minors where Paper stays on one exact version.
     *
     * <p>A run that moves past {@link Platform#VELOCITY_API} leaves {@code network-control} running
     * on an API it was not built for. That is noted rather than refused: the skew is usually
     * harmless, and blocking the proxy's update over it is the worse failure.</p>
     */
    private void resolveVelocity(final Map<String, RemoteFile> newest, final Map<String, String> failures,
                                 final List<String> notes) {
        try {
            final String version = fill.newestStableVersion(Topology.VELOCITY, Platform.VELOCITY_FAMILY);
            newest.put(Topology.VELOCITY, fill.newestStable(Topology.VELOCITY, version));

            if (!Platform.VELOCITY_API.equals(version)) {
                notes.add("the proxy resolves to Velocity " + version + ", and network-control is"
                        + " compiled against " + Platform.VELOCITY_API + " - a plugin running on an"
                        + " API it was not built for. Nothing is blocked; the fix is one line in"
                        + " gradle/libs.versions.toml and a release.");
            }
        } catch (final IOException failed) {
            failures.put(Topology.VELOCITY, failed.getMessage());
        }
    }

    // ---------------------------------------------------------------- comparison

    private Change compare(final String service, final String artifact, final Installation installed,
                           final Map<String, RemoteFile> newest, final Map<String, String> failures,
                           final Map<String, String> unsupported, final Set<String> claimed) {
        final RemoteFile wanted = newest.get(artifact);
        if (wanted == null) {
            final String none = unsupported.get(artifact);
            if (none != null) {
                // Deliberately claims nothing on disk: a jar installed by hand then shows up in
                // UpdatePlan#unclaimed, which is louder than comparing against a file that is absent.
                return Change.unsupported(service, artifact, none);
            }
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

    /**
     * The bot and the updater: one jar each, in a volume of their own, with no {@code plugins/}
     * folder. Both containers run whatever jar is in their volume and fall back to the one baked
     * into the image only when the volume is empty, which is what makes a first deployment possible.
     *
     * <p>The updater's own row is installed like the bot's and cannot take effect during the run
     * that installs it - the new jar waits for the next start, which is the restart.</p>
     */
    private Change resolveStandalone(final Path root, final String artifact,
                                     final Map<String, RemoteFile> newest,
                                     final Map<String, String> failures) {
        final RemoteFile wanted = newest.get(artifact);
        if (wanted == null) {
            return Change.unresolved(artifact, artifact,
                    failures.getOrDefault(artifact, "no source answered for this artefact"));
        }

        final Installation installed = scanFlat(artifact, root.resolve(artifact));
        if (!installed.mounted()) {
            return new Change(artifact, artifact, Change.Status.MOUNT_MISSING, null, wanted,
                    installed.directory() + " is not mounted in this container");
        }
        // No unsupported map: these two come from our own release, which either carries their jar
        // or does not.
        return compare(artifact, artifact, installed, newest, failures, Map.of(), new HashSet<>());
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

        // The hash is the identity, not the URL: the client keys its cache on it. Compared
        // case-insensitively because a hash typed by a person is the one that differs in case.
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

    /** The bot's and the updater's volumes: the jar is in the root, there is no plugins folder. */
    private Installation scanFlat(final String service, final Path directory) {
        try {
            return Installation.scanFlat(service, directory);
        } catch (final IOException failed) {
            log.warn("Could not read {} for {}: {}", directory, service, failed.getMessage());
            return Installation.absent(service, directory);
        }
    }

    private Installation scan(final String service, final Path directory) {
        try {
            return Installation.scan(service, directory);
        } catch (final IOException failed) {
            // A directory that exists but cannot be listed is a mount permissions problem, and it
            // must not read as an empty server.
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
