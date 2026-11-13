package eu.nordtal.season.stewardagent.plan;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.Platform;
import eu.nordtal.season.internalapi.agent.JarName;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.source.GitHubReleases;
import eu.nordtal.season.stewardagent.source.Modrinth;
import eu.nordtal.season.stewardagent.source.PaperFill;
import eu.nordtal.season.stewardagent.source.RemoteFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    private final eu.nordtal.season.stewardagent.plugin.PluginDirectory plugins;

    private final List<Topology.Service> servers;

    /**
     * Resolves against the servers compose.yml labels plus the plugins an admin added.
     *
     * @param servers the servers as compose.yml's labels describe them
     * @param plugins merged in by {@code PluginDirectory#servicesWith}; {@code PluginDirectory#NONE} without a database
     */
    public Resolver(
            final RunSpec config,
            final GitHubReleases github,
            final Modrinth modrinth,
            final PaperFill fill,
            final Clock clock,
            final List<Topology.Service> servers,
            final eu.nordtal.season.stewardagent.plugin.PluginDirectory plugins) {
        this.config = config;
        this.servers = List.copyOf(servers);
        this.github = github;
        this.modrinth = modrinth;
        this.fill = fill;
        this.clock = clock;
        this.plugins = plugins;
    }

    public UpdatePlan resolve() {
        final Map<String, RemoteFile> newest = new LinkedHashMap<>();
        final Map<String, MessageRef> failures = new HashMap<>();
        // `failures` stays apart: only a failure makes the report untrustworthy.
        final Map<String, MessageRef> unsupported = new HashMap<>();
        final List<MessageRef> notes = new ArrayList<>();
        // Season jars our release answered for without a file; the reason is an answer, not an outage.
        final Set<String> unreleased = new HashSet<>();

        final GitHubReleases.Release season = resolveSeason(newest, failures, unreleased);
        resolveModrinth(newest, failures, unsupported, Topology.PACKETEVENTS, config.packetEventsProject(), "paper");
        resolveModrinth(newest, failures, unsupported, Topology.VOICE_CHAT, config.voiceChatProject(), "paper");
        // The loader tells the Velocity jar from the other jar of the same Modrinth project.
        resolveModrinth(
                newest, failures, unsupported, Topology.VOICE_CHAT_PROXY, config.voiceChatProject(), "velocity");
        resolveModrinth(newest, failures, unsupported, Topology.PRISM, config.prismProject(), "paper");
        resolveModrinth(newest, failures, unsupported, Topology.NBT_API, config.nbtApiProject(), "paper");
        resolvePaper(newest, failures);
        resolveVelocity(newest, failures, notes);

        final List<Topology.Service> services = mergeAddedPlugins(newest, failures, unsupported);

        final List<Change> changes = new ArrayList<>();
        final List<UpdatePlan.Unclaimed> unclaimed = new ArrayList<>();
        final List<UpdatePlan.Retiring> retiring = new ArrayList<>();
        final Path root = Path.of(config.volumesRoot());
        compareServices(services, root, newest, failures, unsupported, unreleased, changes, unclaimed, retiring);

        return new UpdatePlan(
                clock.instant(),
                season == null ? null : season.tag(),
                season != null && season.prerelease(),
                List.copyOf(changes),
                List.copyOf(unclaimed),
                List.copyOf(notes),
                List.copyOf(retiring));
    }

    /** The rows of {@code service_plugin}, or none when the database cannot be read. */
    private List<eu.nordtal.season.stewardagent.plugin.ManagedPlugin> readAdded() {
        try {
            return plugins.all();
        } catch (final RuntimeException failed) {
            log.warn("added plugins unreadable, plan holds topology only: cause={}", failed.toString());
            return List.of();
        }
    }

    /**
     * Merges the plugins added in the interface into the fixed topology. An unreadable database loses only those.
     */
    private List<Topology.Service> mergeAddedPlugins(
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Map<String, MessageRef> unsupported) {
        final List<eu.nordtal.season.stewardagent.plugin.ManagedPlugin> added = readAdded();
        final List<Topology.Service> services =
                eu.nordtal.season.stewardagent.plugin.PluginDirectory.servicesWith(servers, added);
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
            final List<UpdatePlan.Unclaimed> unclaimed,
            final List<UpdatePlan.Retiring> retiring) {
        for (final Topology.Service service : services) {
            final Installation installed = scan(service.name(), root.resolve(service.name()));

            // The jars the topology accounts for, by filename prefix. The rest is unclaimed.
            final Set<String> claimed = new HashSet<>();

            final List<String> artifacts = new ArrayList<>(service.plugins());
            artifacts.add(service.kind().fillProject());

            for (final String artifact : artifacts) {
                changes.add(compare(
                        service.name(), artifact, installed, newest, failures, unsupported, unreleased, claimed));
            }

            if (installed.mounted()) {
                retiring.addAll(retired(installed, claimed));
                for (final Installation.Jar jar : installed.plugins()) {
                    if (jar.prefix() != null && !claimed.contains(jar.prefix())) {
                        unclaimed.add(new UpdatePlan.Unclaimed(service.name(), jar.fileName()));
                    }
                }
            }
        }
    }

    /**
     * Finds the files of the service's retired plugins and claims their jars. A row that claims the prefix keeps it.
     */
    private static List<UpdatePlan.Retiring> retired(final Installation installed, final Set<String> claimed) {
        final List<UpdatePlan.Retiring> found = new ArrayList<>();
        for (final Retired gone : Retired.on(installed.service())) {
            if (claimed.stream().anyMatch(gone::owns)) {
                continue;
            }
            final List<String> files = new ArrayList<>();
            installed.plugins().stream().filter(jar -> gone.owns(jar.prefix())).forEach(jar -> {
                files.add(jar.fileName());
                claimed.add(jar.prefix());
            });
            final Path folder =
                    installed.directory().resolve(Installation.PLUGINS).resolve(gone.folder());
            if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                files.add(gone.folder());
            }
            if (!files.isEmpty()) {
                found.add(new UpdatePlan.Retiring(installed.service(), gone.artifact(), files));
            }
        }
        return found;
    }

    /** Asks Modrinth once per artefact id for every added plugin. */
    private void resolveAdded(
            final Map<String, RemoteFile> newest,
            final Map<String, MessageRef> failures,
            final Map<String, MessageRef> unsupported,
            final List<eu.nordtal.season.stewardagent.plugin.ManagedPlugin> added,
            final List<Topology.Service> services) {
        for (final Topology.Service service : services) {
            for (final eu.nordtal.season.stewardagent.plugin.ManagedPlugin plugin : added) {
                if (!plugin.service().equals(service.name())) {
                    continue;
                }
                final String artifact = Topology.addedArtifact(plugin.artifact(), service.kind());
                // One plugin on two services of one kind is one question, and the fixed row wins.
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
            // One call answers for every jar of ours, so one reason covers every row.
            final MessageRef why =
                    TEXTS.report().releaseUnread(config.seasonRepo(), String.valueOf(failed.getMessage()));
            log.warn("season release unresolved: cause={}", failed.getMessage());
            Topology.SEASON_JARS.forEach(artifact -> failures.put(artifact, why));
            return null;
        }

        for (final GitHubReleases.Asset asset : release.assets()) {
            final String prefix = JarName.prefixOf(asset.name());
            // The asset's prefix is the artifact id ('smp-0.2.0.jar' is 'smp'); an extra asset is ignored.
            if (prefix == null || !Topology.SEASON_JARS.contains(prefix)) {
                continue;
            }
            if (asset.digest() == null) {
                // Refused, not fetched unverified: the jar goes straight into a plugins directory.
                failures.put(prefix, TEXTS.report().releaseJarWithoutDigest(release.tag(), asset.name()));
                continue;
            }
            newest.put(
                    prefix,
                    new RemoteFile(
                            prefix,
                            versionOrTag(asset.name(), release.tag()),
                            asset.name(),
                            asset.url(),
                            asset.digest()));
        }

        for (final String artifact : Topology.SEASON_JARS) {
            if (!newest.containsKey(artifact) && !failures.containsKey(artifact)) {
                failures.put(artifact, TEXTS.report().releaseWithoutJar(release.tag(), artifact));
                unreleased.add(artifact);
            }
        }

        return release;
    }

    /**
     * Resolves one Modrinth-hosted plugin. {@link Modrinth.Unsupported} is no outage: a failure row skips the service.
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
            log.info("minecraft build missing, plan row kept: artifact={} version={}", artifact, Platform.MINECRAFT);
            unsupported.put(artifact, TEXTS.report().noBuild(artifact, Platform.MINECRAFT, loader));
        } catch (final IOException failed) {
            failures.put(artifact, TEXTS.report().words(String.valueOf(failed.getMessage())));
        }
    }

    /** Resolves the newest stable build of {@link Platform#MINECRAFT}. */
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

    private Installation scan(final String service, final Path directory) {
        try {
            return Installation.scan(service, directory);
        } catch (final IOException failed) {
            // A directory that exists but cannot be listed is a mount permission problem, not an empty server.
            log.warn("directory unreadable: dir={} service={} cause={}", directory, service, failed.getMessage());
            return Installation.absent(service, directory);
        }
    }

    private static String versionOrTag(final String fileName, final String tag) {
        final String version = JarName.versionOf(fileName);
        if (version != null) {
            return version;
        }
        // The pack is a zip, so the jar rule applies by hand.
        final int dot = fileName.lastIndexOf('.');
        final String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        final int dash = stem.lastIndexOf('-');
        return dash <= 0 ? tag : stem.substring(dash + 1);
    }
}
