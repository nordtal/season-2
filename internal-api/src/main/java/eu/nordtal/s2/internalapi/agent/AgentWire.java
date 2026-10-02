package eu.nordtal.s2.internalapi.agent;

import com.google.gson.annotations.SerializedName;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The routes steward-agent answers and the shapes on them; Docker's own JSON never leaves steward-agent.
 *
 * A path naming {@code {service}} takes a compose service name, one naming {@code {id}} a container or job id.
 */
public final class AgentWire {

    /** The compose service, its default port, and the one service it never recreates. */
    public static final String SERVICE = "steward-agent";

    public static final int PORT = 8081;

    /** What compose.yml says about every service, in any profile: {@link Topology}. */
    public static final String TOPOLOGY = "/api/topology";

    /** Every container of the project with its state and its last sample: {@link Containers}. */
    public static final String CONTAINERS = "/api/containers";

    /** One service's {@link Container}, with the registry digests of its image. */
    public static final String CONTAINER = CONTAINERS + "/{service}";

    /**
     * The longest a run's stop may take: the {@code stop_grace_period}, the kill and its answer.
     *
     * The agent never waits a grace longer than this allows.
     */
    public static final Duration LONGEST_STOP = Duration.ofMinutes(4);

    /** Every running service's image against its registry: an {@link ImageResult}, slow on purpose. */
    public static final String IMAGES = "/api/images";

    /** Server-sent events: {@link LogEvent}s, the backlog first, then the live log. */
    public static final String LOGS = CONTAINER + "/logs";

    /** How many lines the console can fill: {@link LogCapacity}, at most {@code ?max=}. */
    public static final String LOG_CAPACITY = CONTAINER + "/log-capacity";

    /** POST a {@link ConsoleLine}; the server's answer is in its log, not in the response. */
    public static final String CONSOLE = CONTAINER + "/console";

    /** The host's own numbers and Docker's disk use: {@link Host}. */
    public static final String HOST = "/api/host";

    /** How much of the disk one service's volume takes: a {@link Disk}, slow on purpose. */
    public static final String DISK = "/api/volumes/{service}/disk";

    /** Every message bundle the services' jars carry: a list of {@link BundleRef}. */
    public static final String BUNDLES = "/api/bundles";

    /**
     * One {@link MessageBundle}, {@code ?module=} naming the plugin, or none for a standalone jar.
     *
     * A POST of {@link BundleChanges} saves the overrides and answers a {@link SavedBundle}.
     */
    public static final String BUNDLE = BUNDLES + "/{service}";

    /** The sampler's rounds taken after {@code ?after=}, an ISO instant, oldest first: a list of {@link Round}. */
    public static final String SAMPLES = "/api/samples";

    /** The archives on the disk, newest first: a list of {@link Archive}. */
    public static final String BACKUPS = "/api/backups";

    /** One finished archive's bytes, streamed. */
    public static final String BACKUP = BACKUPS + "/{name}";

    /** What every source calls newest against what is installed, flattened for the updates page; slow on purpose. */
    public static final String PLAN = "/api/plan";

    /** The plugins an admin added to one server, and the ones the network gives; POST adds one. */
    public static final String PLUGINS = "/api/plugins/{name}";

    /** Modrinth's answer to {@code ?q=} for one server. */
    public static final String PLUGIN_SEARCH = PLUGINS + "/search";

    private AgentWire() {}

    /** Returns {@code route} with its one placeholder filled in, URL-safe as every caller's value already is. */
    public static String of(final String route, final String value) {
        return route.replaceFirst("\\{[a-z]+}", value);
    }

    /**
     * Every service compose.yml defines, in file order, as its labels describe it, and what a backup saves.
     *
     * @param backupVolumes the volumes mounted under the agent's backup sources, by the name a snapshot carries
     * @param mountedBy for each of those volumes, the services but the agent that mount the same source
     */
    public record Topology(List<Service> services, List<String> backupVolumes, Map<String, List<String>> mountedBy) {

        public Topology {
            services = List.copyOf(services);
            backupVolumes = List.copyOf(backupVolumes);
            // An older agent's answer has no such field.
            mountedBy = mountedBy == null ? Map.of() : Map.copyOf(mountedBy);
        }

        /** A topology that does not say which service runs on which volume. */
        public Topology(final List<Service> services, final List<String> backupVolumes) {
            this(services, backupVolumes, Map.of());
        }

        /** The services that run on a saved volume, which a restore of it stops; empty for one nothing mounts. */
        public List<String> usersOf(final String volume) {
            return List.copyOf(mountedBy.getOrDefault(volume, List.of()));
        }

        /** The services a backup stops while it saves, in file order. */
        public List<String> stoppedForBackup() {
            return services.stream()
                    .filter(Service::stoppedForBackup)
                    .map(Service::name)
                    .toList();
        }

        /** The Minecraft servers, the services with a plugins folder, in file order: the proxy first. */
        public List<eu.nordtal.s2.internalapi.agent.Topology.Service> servers() {
            return services.stream()
                    .map(Service::server)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        /** Whether {@code service} is one of the servers, which decides whether the Plugins tab is drawn. */
        public boolean hasPlugins(final String service) {
            return servers().stream().anyMatch(server -> server.name().equals(service));
        }

        /** The standby that stands in for {@code service}, if it has one. */
        public java.util.Optional<String> standbyOf(final String service) {
            return services.stream()
                    .filter(one -> service.equals(one.standbyOf()))
                    .map(Service::name)
                    .findFirst();
        }

        /** Every standby, in file order, which is the order a swap starts them in. */
        public List<String> standbys() {
            return services.stream()
                    .filter(one -> one.standbyOf() != null)
                    .map(Service::name)
                    .toList();
        }

        /** The services a run renews at that point, in file order. */
        public List<String> renewed(final Renewal when) {
            return services.stream()
                    .filter(one -> one.renewal() == when)
                    .map(Service::name)
                    .toList();
        }
    }

    /** When a run makes a service's container again, which the label {@code eu.nordtal.renew} says. */
    public enum Renewal {
        /** Stopped by a run, made again from a newer image and started: every image of ours a run may stop. */
        RUN,
        /** Made again once everything else is back: an image nobody here builds. */
        AFTER,
        /** Made again last of all, since the run's report goes through it: postgres. */
        LAST
    }

    /**
     * One service of compose.yml, as its labels describe it.
     *
     * @param image the image reference after interpolation, or {@code null} for a service that only builds
     * @param console whether a line can be typed into it, which the label {@code eu.nordtal.console} says
     * @param stoppedForBackup whether a backup stops it while it saves, which the label {@code eu.nordtal.backup} says
     * @param server the server it is, from {@code eu.nordtal.server} and {@code eu.nordtal.plugins}, or none
     * @param standbyOf the service it stands in for, from {@code eu.nordtal.standby-of}, or none
     * @param renewal when a run makes it again, from {@code eu.nordtal.renew}, or never
     */
    public record Service(
            String name,
            @Nullable String image,
            boolean console,
            boolean stoppedForBackup,
            eu.nordtal.s2.internalapi.agent.Topology.@Nullable Service server,
            @Nullable String standbyOf,
            @Nullable Renewal renewal) {

        /** A service that is no server and no standby, and that no run makes again. */
        public Service(
                final String name,
                final @Nullable String image,
                final boolean console,
                final boolean stoppedForBackup) {
            this(name, image, console, stoppedForBackup, null, null, null);
        }
    }

    /**
     * One container, as the service table and a run read it.
     *
     * @param state Docker's word: {@code running}, {@code exited} and so on
     * @param health Docker's health word, or {@code null} for a container without a healthcheck
     * @param sample the sampler's last reading of it, or {@code null} while it is stopped or not yet read
     * @param digests the registry digests of its image, filled only by {@link #CONTAINER}
     */
    public record Container(
            String service,
            String id,
            @Nullable String image,
            @Nullable String imageId,
            String state,
            @Nullable String status,
            @Nullable String health,
            @Nullable String startedAt,
            @Nullable Reading sample,
            @Nullable List<String> digests) {

        public boolean isRunning() {
            return "running".equalsIgnoreCase(state);
        }
    }

    /** Every container of the project, or why the daemon could not be read. */
    public record Containers(boolean reached, @Nullable String message, List<Container> containers) {}

    /** One container's memory and CPU at one moment; the CPU is absent until there are two readings. */
    public record Reading(
            Instant at,
            long memoryBytes,
            long memoryLimitBytes,
            @Nullable Double cpuPercent) {}

    /**
     * One event of a log follow, as the browser receives it in turn.
     *
     * @param event {@code line}, {@code run} (a label above an earlier run's lines), {@code end} or {@code gone}
     */
    public record LogEvent(String event, String data) {}

    public record LogCapacity(int lines) {}

    /** A line for a server's console and who typed it, which the agent logs beside it. */
    public record ConsoleLine(String command, String actor) {}

    /** The host's memory, CPU, load and disk, and how much of the disk Docker's images and volumes take. */
    public record Host(
            @Nullable HostNumbers numbers,
            @Nullable String unreadable,
            @Nullable Long imagesBytes,
            @Nullable Long volumesBytes,
            @Nullable String dockerDiskUnreadable) {}

    /** What {@code /proc} and the root filesystem say. */
    public record HostNumbers(
            double load1,
            int cpus,
            @Nullable Double cpuPercent,
            long memoryTotalBytes,
            long memoryAvailableBytes,
            long diskTotalBytes,
            long diskUsedBytes) {}

    /**
     * One round of the sampler: the host and every running service, each summed over its containers.
     *
     * @param at when it was taken, which is also what a reader asks after, so a restart of the agent loses nothing
     */
    public record Round(Instant at, @Nullable HostNumbers host, Map<String, Reading> services) {}

    /** A volume's size by {@code du}, or {@code null} when it could not be measured. */
    public record Disk(@Nullable Long bytes) {}

    /**
     * One message bundle before its jar is opened.
     *
     * @param module the plugin's data directory under the service, or the empty string for a standalone jar
     */
    public record BundleRef(String service, String module, boolean writable) {}

    /** One language's text for one key; a {@code null} text resets it to the packaged one. */
    public record TextChange(
            String key, String language, @Nullable String text) {}

    public record BundleChanges(List<TextChange> changes) {}

    /** The bundle as it reads after a save, and every dropped placeholder the new text no longer carries. */
    public record SavedBundle(MessageBundle bundle, List<String> warnings) {}

    /**
     * One file in the backup directory; a {@code partial} one is being written or died halfway.
     *
     * @param restoresInto the volume a restore of it replaces, or the database's name for a dump, typed to confirm
     */
    public record Archive(
            String name,
            long bytes,
            String human,
            Instant modified,
            boolean partial,
            @Nullable String restoresInto) {}

    /**
     * One server's plugins, sorted by name; {@code mounted} is false when the agent cannot see the volume.
     *
     * @param loader {@code paper} or {@code velocity}, what a search on this server is filtered to
     */
    public record Plugins(String service, String loader, String gameVersion, boolean mounted, List<Plugin> plugins) {}

    /** Which list a plugin belongs in: built here, given by the network, or added by an admin. */
    public enum PluginGroup {
        @SerializedName("nordtal")
        NORDTAL,
        @SerializedName("preinstalled")
        PREINSTALLED,
        @SerializedName("added")
        ADDED
    }

    /**
     * One plugin of a server: a jar on disk, one the network gives that is missing, or one added and not installed.
     *
     * @param name the title for an added plugin and any jar Modrinth published, else the jar's filename prefix
     * @param rank a Nordtal plugin's place in its list, before the alphabet
     * @param release the release whose run installed the jar, absent for a jar no run noted
     * @param dataFolder {@code plugins/<dataFolder>/} from the jar's own descriptor, absent when it could not be read
     * @param artifact an added plugin's Modrinth slug, or a given plugin's artefact id when it is not on disk
     */
    public record Plugin(
            String name,
            PluginGroup group,
            @Nullable Integer rank,
            boolean running,
            boolean removable,
            @Nullable String filePrefix,
            @Nullable String fileName,
            @Nullable String version,
            @Nullable String release,
            @Nullable String dataFolder,
            @Nullable String artifact,
            @Nullable String projectId,
            @Nullable Instant added,
            @Nullable String addedBy,
            @Nullable String iconUrl,
            @Nullable String pageUrl) {

        /** This plugin with the release that installed its jar, or with none. */
        public Plugin released(final @Nullable String installedBy) {
            return new Plugin(
                    name,
                    group,
                    rank,
                    running,
                    removable,
                    filePrefix,
                    fileName,
                    version,
                    installedBy,
                    dataFolder,
                    artifact,
                    projectId,
                    added,
                    addedBy,
                    iconUrl,
                    pageUrl);
        }
    }

    /** Modrinth's hits for one query, already filtered to the server's loader and Minecraft version. */
    public record PluginSearch(String service, String loader, String gameVersion, String query, List<PluginHit> hits) {}

    /**
     * One search hit; {@code added} is an admin's plugin here already, {@code fixed} one the network gives.
     *
     * @param iconUrl on {@code cdn.modrinth.com}, which the browser loads directly
     */
    public record PluginHit(
            String projectId,
            String slug,
            String title,
            @Nullable String description,
            @Nullable String iconUrl,
            String pageUrl,
            long downloads,
            boolean added,
            boolean fixed) {}

    /** An answer that is not the one asked for, with the sentence to show and where it came from. */
    public record Refusal(String error, String where) {}
}
