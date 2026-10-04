package eu.nordtal.s2.stewardagent.plan;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.common.Platform;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.internalapi.agent.JarName;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.stewardagent.config.RunSpec;
import eu.nordtal.s2.stewardagent.source.Checksum;
import eu.nordtal.s2.stewardagent.source.GitHubReleases;
import eu.nordtal.s2.stewardagent.source.Modrinth;
import eu.nordtal.s2.stewardagent.source.PaperFill;
import eu.nordtal.s2.stewardagent.source.RemoteFile;
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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Compares what every source calls newest with what is on disk, writing nothing.
 *
 * One source's outage costs only its own rows and never reads as unchanged.
 */
@Slf4j
public final class Resolver {

    private final RunSpec config;
    private final GitHubReleases github;
    private final Modrinth modrinth;
    private final PaperFill fill;
    private final Clock clock;
    private final eu.nordtal.s2.stewardagent.plugin.PluginDirectory plugins;

    /** Where the proxy's pack is read; {@code null} without a database. */
    private final @Nullable SettingStore settings;

    /** The servers as compose.yml's labels describe them, before the added plugins are folded in. */
    private final List<Topology.Service> servers;

    /**
     * Resolves against the servers compose.yml labels plus the plugins an admin added.
     *
     * @param servers the servers as compose.yml's labels describe them
     * @param plugins merged in by {@code PluginDirectory#servicesWith}; {@code PluginDirectory#NONE} without a database
     * @param settings where the proxy's pack is read; {@code null} leaves the pack unknown
     */
    public Resolver(
            final RunSpec config,
            final GitHubReleases github,
            final Modrinth modrinth,
            final PaperFill fill,
            final Clock clock,
            final List<Topology.Service> servers,
            final eu.nordtal.s2.stewardagent.plugin.PluginDirectory plugins,
            final @Nullable SettingStore settings) {
        this.config = config;
        this.servers = List.copyOf(servers);
        this.github = github;
        this.modrinth = modrinth;
        this.fill = fill;
        this.clock = clock;
        this.plugins = plugins;
        this.settings = settings;
    }

    public UpdatePlan resolve() {
        final Map<String, RemoteFile> newest = new LinkedHashMap<>();
        final Map<String, MessageRef> failures = new HashMap<>();
        // Apart from `failures`: both mean no file, only a failure makes the report untrustworthy.
        final Map<String, MessageRef> unsupported = new HashMap<>();
        final List<MessageRef> notes = new ArrayList<>();
        // Season jars our release answered for without a file; the reason is an answer, not an outage.
        final Set<String> unreleased = new HashSet<>();

        final GitHubReleases.Release season = resolveSeason(newest, failures, unreleased);
        resolveDisplayTags(newest, failures);
        resolveModrinth(newest, failures, unsupported, Topology.PACKETEVENTS, config.packetEventsProject(), "paper");
        resolveModrinth(newest, failures, unsupported, Topology.VOICE_CHAT, config.voiceChatProject(), "paper");
        // The same Modrinth project asked again for the Velocity build; the loader tells the two jars apart.
        resolveModrinth(
                newest, failures, unsupported, Topology.VOICE_CHAT_PROXY, config.voiceChatProject(), "velocity");
        resolveModrinth(newest, failures, unsupported, Topology.CORE_PROTECT, config.coreProtectProject(), "paper");
        resolvePaper(newest, failures);
        resolveVelocity(newest, failures, notes);

        final List<Topology.Service> services = mergeAddedPlugins(newest, failures, unsupported);

        final List<Change> changes = new ArrayList<>();
        final List<UpdatePlan.Unclaimed> unclaimed = new ArrayList<>();
        final Path root = Path.of(config.volumesRoot());
        compareServices(services, root, newest, failures, unsupported, unreleased, changes, unclaimed);

        changes.add(resolvePack(newest, failures, unreleased));

        return new UpdatePlan(
                clock.instant(),
                season == null ? null : season.tag(),
                season != null && season.prerelease(),
                List.copyOf(changes),
                List.copyOf(unclaimed),
                List.copyOf(notes));
    }

    /** The rows of {@code service_plugin}, or none when the database cannot be read. */
    private List<eu.nordtal.s2.stewardagent.plugin.ManagedPlugin> readAdded() {
        try {
            return plugins.all();
        } catch (final RuntimeException failed) {
            log.warn(
                    "Could not read the added plugins, so this plan carries only the ones the" + " topology names: {}",
                    failed.toString());
            return List.of();
        }
    }

    /** The fixed topology plus the plugins added in the interface; an unreadable database costs only the added rows. */
    private List<Topology.Service> mergeAddedPlugins(
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Map<String, MessageRef> unsupported) {
        final List<eu.nordtal.s2.stewardagent.plugin.ManagedPlugin> added = readAdded();
        final List<Topology.Service> services =
                eu.nordtal.s2.stewardagent.plugin.PluginDirectory.servicesWith(servers, added);
        resolveAdded(newest, failures, unsupported, added, services);
        return services;
    }

    /**
     * Compares every service's installed jars against what is newest, filling {@code changes} and {@code unclaimed}.
     */
    private void compareServices(
            final List<Topology.Service> services,
            final Path root,
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Map<String, MessageRef> unsupported,
            final Set<String> unreleased,
            final List<Change> changes,
            final List<UpdatePlan.Unclaimed> unclaimed) {
        for (final Topology.Service service : services) {
            final Installation installed = scan(service.name(), root.resolve(service.name()));

            // Every jar the topology accounts for on this service, by filename prefix; the rest is unclaimed.
            final Set<String> claimed = new HashSet<>();

            final List<String> artifacts = new ArrayList<>(service.plugins());
            artifacts.add(service.kind().fillProject());

            for (final String artifact : artifacts) {
                changes.add(compare(
                        service.name(), artifact, installed, newest, failures, unsupported, unreleased, claimed));
            }

            if (installed.mounted()) {
                for (final Installation.Jar jar : installed.plugins()) {
                    if (jar.prefix() != null && !claimed.contains(jar.prefix())) {
                        unclaimed.add(new UpdatePlan.Unclaimed(service.name(), jar.fileName()));
                    }
                }
            }
        }
    }

    /** Asks Modrinth once per artefact id for every added plugin, so one slug on two loaders is two questions. */
    private void resolveAdded(
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Map<String, MessageRef> unsupported,
            final List<eu.nordtal.s2.stewardagent.plugin.ManagedPlugin> added,
            final List<Topology.Service> services) {
        for (final Topology.Service service : services) {
            for (final eu.nordtal.s2.stewardagent.plugin.ManagedPlugin plugin : added) {
                if (!plugin.service().equals(service.name())) {
                    continue;
                }
                final String artifact = Topology.addedArtifact(plugin.artifact(), service.kind());
                // Already answered: one plugin on two services of one kind is one question; the fixed row wins.
                if (newest.containsKey(artifact)
                        || unsupported.containsKey(artifact)
                        || failures.containsKey(artifact)) {
                    continue;
                }
                resolveModrinth(
                        newest,
                        failures,
                        unsupported,
                        artifact,
                        plugin.projectId(),
                        service.kind().modrinthLoader());
            }
        }
    }

    private GitHubReleases.@Nullable Release resolveSeason(
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Set<String> unreleased) {
        final GitHubReleases.Release release;
        try {
            release = github.latest(config.seasonRepo());
        } catch (final IOException failed) {
            // Our own jars and the pack come from this one call, so one reason covers every row.
            final MessageRef why =
                    TEXTS.report().releaseUnread(config.seasonRepo(), String.valueOf(failed.getMessage()));
            log.warn("Season release unresolved - {}", failed.getMessage());
            Topology.SEASON_JARS.forEach(artifact -> failures.put(artifact, why));
            failures.put(Topology.RESOURCE_PACK, why);
            return null;
        }

        for (final GitHubReleases.Asset asset : release.assets()) {
            final String prefix = JarName.prefixOf(asset.name());
            // The asset's prefix is the artifact id ('smp-0.2.0.jar' is 'smp'); an extra asset is ignored.
            if (prefix != null && Topology.SEASON_JARS.contains(prefix)) {
                newest.put(
                        prefix,
                        new RemoteFile(
                                prefix, versionOrTag(asset.name(), release.tag()), asset.name(), asset.url(), null));
            }
        }

        for (final String artifact : Topology.SEASON_JARS) {
            if (!newest.containsKey(artifact)) {
                failures.put(artifact, TEXTS.report().releaseWithoutJar(release.tag(), artifact));
                unreleased.add(artifact);
            }
        }

        resolvePackAsset(release, newest, failures, unreleased);
        return release;
    }

    /** The pack zip and the SHA-1 asset beside it, which is read rather than computed. */
    private void resolvePackAsset(
            final GitHubReleases.Release release,
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Set<String> unreleased) {
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
            failures.put(Topology.RESOURCE_PACK, TEXTS.report().releaseWithoutPack(release.tag()));
            unreleased.add(Topology.RESOURCE_PACK);
            return;
        }
        if (sha1 == null) {
            // Refused, not worked around: the client gets URL and hash together and rejects a mismatch.
            failures.put(Topology.RESOURCE_PACK, TEXTS.report().releaseWithoutSha1(release.tag(), zip.name()));
            return;
        }

        try {
            newest.put(
                    Topology.RESOURCE_PACK,
                    new RemoteFile(
                            Topology.RESOURCE_PACK,
                            versionOrTag(zip.name(), release.tag()),
                            zip.name(),
                            zip.url(),
                            Checksum.sha1(github.readText(sha1))));
        } catch (final IOException failed) {
            failures.put(
                    Topology.RESOURCE_PACK,
                    TEXTS.report().sha1Unread(sha1.name(), String.valueOf(failed.getMessage())));
        }
    }

    private void resolveDisplayTags(final Map<String, RemoteFile> newest, final Map<String, MessageRef> failures) {
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
                failures.put(
                        Topology.DISPLAY_TAGS, TEXTS.report().releaseWithoutJar(release.tag(), Topology.DISPLAY_TAGS));
                return;
            }
            newest.put(
                    Topology.DISPLAY_TAGS,
                    new RemoteFile(
                            Topology.DISPLAY_TAGS,
                            versionOrTag(jar.name(), release.tag()),
                            jar.name(),
                            jar.url(),
                            null));
        } catch (final IOException failed) {
            failures.put(
                    Topology.DISPLAY_TAGS,
                    TEXTS.report().releaseUnread(config.displayTagsRepo(), String.valueOf(failed.getMessage())));
        }
    }

    /**
     * One Modrinth-hosted plugin; {@link Modrinth.Unsupported} is not an outage, since a failure row skips the service.
     */
    private void resolveModrinth(
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Map<String, MessageRef> unsupported,
            final String artifact,
            final String projectId,
            final String loader) {
        try {
            newest.put(artifact, modrinth.newest(artifact, projectId, Platform.MINECRAFT, loader));
        } catch (final Modrinth.Unsupported none) {
            log.info(
                    "{} has no build for Minecraft {} - the row stays in the plan and installs"
                            + " itself when one appears",
                    artifact,
                    Platform.MINECRAFT);
            unsupported.put(artifact, TEXTS.report().noBuild(artifact, Platform.MINECRAFT, loader));
        } catch (final IOException failed) {
            failures.put(artifact, TEXTS.report().words(String.valueOf(failed.getMessage())));
        }
    }

    /** The newest stable build of {@link Platform#MINECRAFT}, an exact version rather than a family. */
    private void resolvePaper(final Map<String, RemoteFile> newest, final Map<String, MessageRef> failures) {
        try {
            newest.put(Topology.PAPER, fill.newestStable(Topology.PAPER, Platform.MINECRAFT));
        } catch (final IOException failed) {
            failures.put(Topology.PAPER, TEXTS.report().words(String.valueOf(failed.getMessage())));
        }
    }

    /**
     * The newest stable build inside {@link Platform#VELOCITY_FAMILY}.
     *
     * Moving past {@link Platform#VELOCITY_API} is noted, not refused.
     */
    private void resolveVelocity(
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final List<MessageRef> notes) {
        try {
            final String version = fill.newestStableVersion(Topology.VELOCITY, Platform.VELOCITY_FAMILY);
            newest.put(Topology.VELOCITY, fill.newestStable(Topology.VELOCITY, version));

            if (!Platform.VELOCITY_API.equals(version)) {
                notes.add(TEXTS.report().velocityAhead(version, Platform.VELOCITY_API));
            }
        } catch (final IOException failed) {
            failures.put(Topology.VELOCITY, TEXTS.report().words(String.valueOf(failed.getMessage())));
        }
    }

    private Change compare(
            final String service,
            final String artifact,
            final Installation installed,
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Map<String, MessageRef> unsupported,
            final Set<String> unreleased,
            final Set<String> claimed) {
        final RemoteFile wanted = newest.get(artifact);
        if (wanted == null) {
            final MessageRef none = unsupported.get(artifact);
            if (none != null) {
                // Claims nothing on disk, so a hand-installed jar shows up in UpdatePlan#unclaimed.
                return Change.unsupported(service, artifact, none);
            }
            final MessageRef why =
                    failures.getOrDefault(artifact, TEXTS.report().noSource(artifact));
            final Installation.Jar kept =
                    unreleased.contains(artifact) && installed.mounted() ? installed.withPrefix(artifact) : null;
            if (kept != null) {
                claimed.add(artifact);
                return new Change(service, artifact, Change.Status.NOT_IN_RELEASE, kept.fileName(), null, why);
            }
            return Change.unresolved(service, artifact, why);
        }

        final String prefix = JarName.prefixOf(wanted.fileName());
        if (prefix != null) {
            claimed.add(prefix);
        }

        if (!installed.mounted()) {
            return new Change(
                    service,
                    artifact,
                    Change.Status.MOUNT_MISSING,
                    null,
                    wanted,
                    TEXTS.report().notMounted(installed.directory().toString()));
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

    private Change resolvePack(
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Set<String> unreleased) {
        final RemoteFile wanted = newest.get(Topology.RESOURCE_PACK);
        final MessageRef why =
                failures.getOrDefault(Topology.RESOURCE_PACK, TEXTS.report().noSource(Topology.RESOURCE_PACK));
        if (wanted == null && !unreleased.contains(Topology.RESOURCE_PACK)) {
            return Change.unresolved(Topology.PROXY, Topology.RESOURCE_PACK, why);
        }

        if (settings == null) {
            return Change.unresolved(
                    Topology.PROXY, Topology.RESOURCE_PACK, TEXTS.report().noDatabase());
        }
        final PackState state;
        try {
            state = PackState.read(settings);
        } catch (final RuntimeException failed) {
            return Change.unresolved(
                    Topology.PROXY,
                    Topology.RESOURCE_PACK,
                    TEXTS.report().proxyPackUnread(String.valueOf(failed.getMessage())));
        }

        if (wanted == null) {
            // A release without a pack keeps the installed one, like a season jar; only an empty proxy fails.
            return state.present()
                    ? new Change(
                            Topology.PROXY,
                            Topology.RESOURCE_PACK,
                            Change.Status.NOT_IN_RELEASE,
                            state.sha1(),
                            null,
                            why)
                    : Change.unresolved(Topology.PROXY, Topology.RESOURCE_PACK, why);
        }

        if (!state.present()) {
            return new Change(
                    Topology.PROXY,
                    Topology.RESOURCE_PACK,
                    Change.Status.MISSING,
                    null,
                    wanted,
                    state.url() == null
                            ? TEXTS.report().proxyWithoutPack()
                            : TEXTS.report().proxyPackWithoutSha1());
        }

        // The hash is the identity, compared case-insensitively since a typed hash may differ in case.
        final Checksum checksum = wanted.checksum();
        final String wantedSha1 = checksum == null ? null : checksum.hex();
        if (wantedSha1 != null && wantedSha1.equalsIgnoreCase(state.sha1())) {
            return new Change(
                    Topology.PROXY, Topology.RESOURCE_PACK, Change.Status.UP_TO_DATE, state.sha1(), wanted, null);
        }
        return new Change(Topology.PROXY, Topology.RESOURCE_PACK, Change.Status.OUTDATED, state.sha1(), wanted, null);
    }

    private Installation scan(final String service, final Path directory) {
        try {
            return Installation.scan(service, directory);
        } catch (final IOException failed) {
            // A directory that exists but cannot be listed is a mount permission problem, not an empty server.
            log.warn("Could not read {} for {}: {}", directory, service, failed.getMessage());
            return Installation.absent(service, directory);
        }
    }

    private static String versionOrTag(final String fileName, final String tag) {
        final String version = JarName.versionOf(fileName);
        if (version != null) {
            return version;
        }
        // A zip, not a jar (nordtal-resource-pack-0.2.0.zip), so the same rule is applied by hand.
        final int dot = fileName.lastIndexOf('.');
        final String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        final int dash = stem.lastIndexOf('-');
        return dash <= 0 ? tag : stem.substring(dash + 1);
    }
}
