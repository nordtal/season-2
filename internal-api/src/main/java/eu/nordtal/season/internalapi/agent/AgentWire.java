package eu.nordtal.season.internalapi.agent;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import eu.nordtal.season.common.id.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The routes of steward-agent and their records.
 * A path with {@code {service}} takes a compose service name, one with {@code {id}} a container or job id.
 */
public final class AgentWire {

    /** The compose service name of the agent, which never recreates itself. */
    public static final String SERVICE = "steward-agent";

    public static final int PORT = 8081;

    /** What compose.yml says about every service in any profile: {@link Topology}. */
    public static final String TOPOLOGY = "/api/topology";

    public static final String CONTAINERS = "/api/containers";

    public static final String CONTAINER = CONTAINERS + "/{service}";

    /** The longest that a run's stop may take: the {@code stop_grace_period}, the kill and its answer. */
    public static final Duration LONGEST_STOP = Duration.ofMinutes(4);

    public static final String IMAGES = "/api/images";

    /** Server-sent events: {@link LogEvent}s, first the backlog, then the live log. */
    public static final String LOGS = CONTAINER + "/logs";

    public static final String LOG_CAPACITY = CONTAINER + "/log-capacity";

    /** POST a {@link ConsoleLine}. The server's answer is in its log, not in the response. */
    public static final String CONSOLE = CONTAINER + "/console";

    /** The host's own numbers and Docker's disk use: {@link Host}. */
    public static final String HOST = "/api/host";

    /** The disk that the volume of one service uses: a {@link Disk}. Slow on purpose. */
    public static final String DISK = "/api/volumes/{service}/disk";

    public static final String BUNDLES = "/api/bundles";

    /**
     * One {@link MessageBundle} with its texts and documents.
     * {@code ?module=} names the plugin; without it the jar is standalone.
     */
    public static final String BUNDLE = BUNDLES + "/{service}";

    /** The descriptor of every jar of ours on the stack: a list of {@link Descriptor}. */
    public static final String DESCRIPTORS = "/api/descriptors";

    /** The sampler's rounds after {@code ?after=} (an ISO instant), oldest first: a list of {@link Round}. */
    public static final String SAMPLES = "/api/samples";

    public static final String BACKUPS = "/api/backups";

    public static final String BACKUP = BACKUPS + "/{name}";

    /**
     * The newest version of every source against the installed one, flattened for the updates page.
     * Slow on purpose.
     */
    public static final String PLAN = "/api/plan";

    /** The plugins that an admin added to one server, and those the network gives. POST adds one. */
    public static final String PLUGINS = "/api/plugins/{name}";

    public static final String PLUGIN_SEARCH = PLUGINS + "/search";

    /** The config files of the plugins on one server that are not Nordtal's: a {@link PluginFiles}. */
    public static final String PLUGIN_FILES = PLUGINS + "/files";

    /** One of those files, named by {@code ?plugin=} and {@code ?path=}: GET reads it, POST saves it. */
    public static final String PLUGIN_FILE = PLUGINS + "/file";

    private AgentWire() {}

    public static String of(final String route, final String value) {
        return route.replaceFirst("\\{[a-z]+}", value);
    }

    /**
     * Every service of compose.yml in file order, as its labels describe it, and what a backup saves.
     *
     * @param backupVolumes the volumes mounted under the agent's backup sources, by the name a snapshot carries
     * @param mountedBy for each of those volumes, the services except the agent that mount the same source
     */
    public record Topology(List<Service> services, List<String> backupVolumes, Map<String, List<String>> mountedBy) {

        public Topology {
            services = List.copyOf(services);
            backupVolumes = List.copyOf(backupVolumes);
            mountedBy = mountedBy == null ? Map.of() : Map.copyOf(mountedBy);
        }

        public Topology(final List<Service> services, final List<String> backupVolumes) {
            this(services, backupVolumes, Map.of());
        }

        public List<String> usersOf(final String volume) {
            return List.copyOf(mountedBy.getOrDefault(volume, List.of()));
        }

        public List<String> stoppedForBackup() {
            return services.stream()
                    .filter(Service::stoppedForBackup)
                    .map(Service::name)
                    .toList();
        }

        /**
         * Returns the Minecraft servers in file order, the proxy first.
         * They are the services with a plugins folder.
         */
        public List<eu.nordtal.season.internalapi.agent.Topology.Service> servers() {
            return services.stream()
                    .map(Service::server)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        public boolean hasPlugins(final String service) {
            return servers().stream().anyMatch(server -> server.name().equals(service));
        }

        public java.util.Optional<String> standbyOf(final String service) {
            return services.stream()
                    .filter(one -> service.equals(one.standbyOf()))
                    .map(Service::name)
                    .findFirst();
        }

        /** Returns every standby in file order, which is the order a swap starts them in. */
        public List<String> standbys() {
            return services.stream()
                    .filter(one -> one.standbyOf() != null)
                    .map(Service::name)
                    .toList();
        }

        public List<String> oneShots() {
            return services.stream().filter(Service::oneShot).map(Service::name).toList();
        }

        public List<String> renewed(final Renewal when) {
            return services.stream()
                    .filter(one -> one.renewal() == when)
                    .map(Service::name)
                    .toList();
        }
    }

    /**
     * Where the network page draws a service and what it is wired to, from four labels of compose.yml.
     *
     * @param section the heading it is grouped under ({@code eu.nordtal.section})
     * @param entry whether players reach it from outside ({@code eu.nordtal.entry})
     * @param reaches the services it sends requests to ({@code eu.nordtal.reaches})
     * @param storesIn the services it keeps its data in ({@code eu.nordtal.stores-in})
     */
    public record Wiring(String section, boolean entry, List<String> reaches, List<String> storesIn) {

        public Wiring {
            reaches = List.copyOf(reaches);
            storesIn = List.copyOf(storesIn);
        }
    }

    /** When a run recreates the container of a service ({@code eu.nordtal.renew}). */
    public enum Renewal {
        /**
         * A run stops the container, recreates it from a newer image and starts it.
         * Applies to every image of ours that a run may stop.
         */
        RUN,
        /** Recreated once everything else is back, for an image that nobody here builds. */
        AFTER,
        /** Recreated last, because the run's report goes through it: postgres. */
        LAST
    }

    /**
     * One service of compose.yml, as its labels describe it.
     *
     * @param image the image reference after interpolation, or {@code null} for a service that is only built
     * @param console whether a line can be typed into it ({@code eu.nordtal.console})
     * @param stoppedForBackup whether a backup stops it while it saves ({@code eu.nordtal.backup})
     * @param server the server it is ({@code eu.nordtal.server}, {@code eu.nordtal.plugins}), or none
     * @param standbyOf the service it stands in for ({@code eu.nordtal.standby-of}), or none
     * @param renewal when a run recreates it ({@code eu.nordtal.renew}), or never
     * @param wiring where the network page draws it, or none if the page leaves it out
     * @param oneShot whether it runs once and exits (compose's {@code restart} is {@code no})
     */
    public record Service(
            String name,
            @Nullable String image,
            boolean console,
            boolean stoppedForBackup,
            eu.nordtal.season.internalapi.agent.Topology.@Nullable Service server,
            @Nullable String standbyOf,
            @Nullable Renewal renewal,
            @Nullable Wiring wiring,
            boolean oneShot) {

        /** A long-running service that the network page does not draw. */
        public Service(
                final String name,
                final @Nullable String image,
                final boolean console,
                final boolean stoppedForBackup,
                final eu.nordtal.season.internalapi.agent.Topology.@Nullable Service server,
                final @Nullable String standbyOf,
                final @Nullable Renewal renewal) {
            this(name, image, console, stoppedForBackup, server, standbyOf, renewal, null, false);
        }

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
     * One container.
     *
     * @param state Docker's state word, such as {@code running} or {@code exited}
     * @param health Docker's health word, or {@code null} without a healthcheck
     * @param sample the sampler's last reading, or {@code null} while the container is stopped or unread
     * @param digests the registry digests of its image, filled by {@link #CONTAINER} only
     * @param finishedAt when its process last ended, or {@code null} while it runs or if it never ran
     * @param exitCode the exit code of that process, or {@code null} if {@code finishedAt} is {@code null}
     * @param restarts how often the restart policy started it again since it was created
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
            @Nullable List<String> digests,
            @Nullable String finishedAt,
            @Nullable Integer exitCode,
            int restarts) {

        public boolean isRunning() {
            return "running".equalsIgnoreCase(state);
        }
    }

    /** Every container of the project, or the reason that the daemon could not be read. */
    public record Containers(boolean reached, @Nullable String message, List<Container> containers) {}

    /** The memory and CPU of one container at one moment. The CPU is absent until there are two readings. */
    public record Reading(
            Instant at,
            long memoryBytes,
            long memoryLimitBytes,
            @Nullable Double cpuPercent) {}

    /**
     * One event of a log follow.
     *
     * @param event {@code line}, {@code run} (a label above the lines of an earlier run), {@code end} or {@code gone}
     */
    public record LogEvent(String event, String data) {}

    public record LogCapacity(int lines) {}

    /** A line for a server's console and who typed it. The agent logs both. */
    public record ConsoleLine(String command, Actor actor) {}

    /** The host's memory, CPU, load and disk, and the disk that Docker's images and volumes use. */
    public record Host(
            @Nullable HostNumbers numbers,
            @Nullable String unreadable,
            @Nullable Long imagesBytes,
            @Nullable Long volumesBytes,
            @Nullable String dockerDiskUnreadable) {}

    /** The values of {@code /proc} and the root filesystem. */
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
     * @param at when the round was taken. A reader asks for rounds after this instant.
     */
    public record Round(Instant at, @Nullable HostNumbers host, Map<String, Reading> services) {}

    /** The size of a volume by {@code du}, or {@code null} if it could not be measured. */
    public record Disk(@Nullable Long bytes) {}

    /**
     * A message bundle before its jar is opened.
     *
     * @param module the plugin's name as its jar is named, or the empty string for a standalone jar
     */
    public record BundleRef(String service, String module) {}

    /**
     * The {@code nordtal-plugin.json} of one jar on one service.
     *
     * @param id the service name under which its groups of settings are published, such as {@code smp}
     * @param editors the custom editor of a group by group name, instead of the form built from its schema
     */
    public record Descriptor(String service, String id, Map<String, String> editors) {}

    /**
     * One file in the backup directory. A {@code partial} file is being written or was cut off.
     *
     * @param restoresInto the volume that a restore replaces, or the database's name for a dump, typed to confirm
     * @param offsite whether the offsite repository holds a copy
     * @param inBackup whether the volume is still in the backup set
     */
    public record Archive(
            String name,
            long bytes,
            String human,
            Instant modified,
            boolean partial,
            @Nullable String restoresInto,
            boolean offsite,
            boolean inBackup) {}

    /**
     * The plugins of one server, sorted by name. {@code mounted} is false if the agent cannot see the volume.
     *
     * @param loader {@code paper} or {@code velocity}
     */
    public record Plugins(String service, String loader, String gameVersion, boolean mounted, List<Plugin> plugins) {}

    /** The list a plugin belongs in: built here, given by the network or added by an admin. */
    public enum PluginGroup {
        @SerializedName("nordtal")
        NORDTAL,
        @SerializedName("preinstalled")
        PREINSTALLED,
        @SerializedName("added")
        ADDED
    }

    /**
     * One plugin of a server: a jar on disk, a given plugin that is missing, or an added plugin that is not installed.
     *
     * @param name the title of an added plugin and of any jar Modrinth published, otherwise the jar's filename prefix
     * @param rank the place in its list, before the alphabet
     * @param release the release whose run installed the jar, absent if no run noted it
     * @param dataFolder {@code plugins/<dataFolder>/} from the jar's descriptor, absent if unreadable
     * @param artifact the Modrinth slug of an added plugin, or the artefact id of a given plugin that is not on disk
     * @param addedBy who added an added plugin
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
            @Nullable Actor addedBy,
            @Nullable String iconUrl,
            @Nullable String pageUrl) {

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

    /** The config files of one server's foreign plugins, by plugin then path. */
    public record PluginFiles(String service, boolean mounted, List<PluginFile> files) {}

    /**
     * One text config file in {@code plugins/<plugin>/}.
     *
     * @param plugin the data folder, which names the file together with {@code path}
     * @param name the plugin's name where a jar claims the folder, absent for a folder no jar claims
     * @param iconUrl the icon the plugins tab shows, absent without one
     * @param path the path inside the folder, with {@code /} between segments
     */
    public record PluginFile(
            String service,
            String plugin,
            @Nullable String name,
            @Nullable String iconUrl,
            String path,
            long size,
            boolean readable,
            boolean writable) {}

    /** @param revision the fingerprint of {@code text}, which a save names */
    public record PluginFileDocument(PluginFile file, String text, String revision) {}

    /** A save of a whole file, refused when {@code revision} is no longer the file's. */
    public record PluginFileSave(String text, String revision, Actor by) {}

    /** @param restartRequired always true: a save only writes the file, and the server reads it when it starts */
    public record PluginFileSaved(PluginFile file, String revision, boolean restartRequired) {}

    /** Modrinth's hits for one query, filtered to the server's loader and Minecraft version. */
    public record PluginSearch(String service, String loader, String gameVersion, String query, List<PluginHit> hits) {}

    /**
     * One search hit. {@code added} marks a plugin that an admin added to this server.
     * {@code fixed} marks one that the network gives.
     *
     * @param iconUrl a URL on {@code cdn.modrinth.com}, which the browser loads directly
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

    /**
     * What a run would do now: every artefact with its status, and the notes a run would write.
     *
     * @param resolvedAt when the agent asked the sources
     */
    public record Resolve(
            Instant resolvedAt,
            @Nullable String seasonTag,
            boolean seasonPrerelease,
            boolean hasWork,
            boolean hasFailures,
            List<ResolvedChange> changes,
            List<Note> notes) {}

    /** One note of a run, by step and outcome. {@code service} is absent for a note that belongs to no service. */
    public record Note(
            String step, String outcome, @Nullable String service, Message what) {}

    /** A message as data: its key and each value with its kind. A value is the JSON it already is. */
    public record Message(String key, JsonObject args) {}

    /**
     * One artefact in a resolve. An unresolved artefact is unknown, never nothing to do.
     *
     * @param service absent for the resource pack
     * @param held work that a run would not do, because another row of the same service could not be checked
     * @param version the version as its publisher states it, for reading and not for comparing
     * @param fileName the file that would be installed, which is the value compared
     */
    public record ResolvedChange(
            @Nullable String service,
            String artifact,
            String status,
            boolean work,
            boolean failure,
            boolean held,
            @Nullable String installed,
            @Nullable String version,
            @Nullable String fileName,
            @Nullable Message reason) {}

    /**
     * A plugin added to one server, which the next run installs.
     *
     * @param fileName the file that would arrive
     */
    public record PluginAdded(String service, String artifact, String filePrefix, String fileName, String version) {}

    /** The Modrinth project that an admin chose to install, as the browser sends it. Nothing else of it is trusted. */
    public record PluginAsk(
            @Nullable String projectId,
            @Nullable String slug,
            @Nullable String title,
            @Nullable String iconUrl) {}

    /** A plugin to add and who asked. {@code steward} takes the actor from the session, never from the browser. */
    public record AddPlugin(PluginAsk plugin, Actor by) {}

    /** An answer other than the one asked for: the sentence to show and where it came from. */
    public record Refusal(String error, String where) {}
}
