package eu.nordtal.s2.internalapi.agent;

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

    /** POST, answered by a {@link RedeployResult}; {@code {id}} is the container. */
    public static final String STOP = "/api/stop/{id}";

    /**
     * The longest a stop through {@link #STOP} may take: the {@code stop_grace_period}, the kill and its answer.
     *
     * The agent never waits a grace longer than this allows, and the client waits this long.
     */
    public static final Duration LONGEST_STOP = Duration.ofMinutes(4);

    public static final String START = "/api/start/{id}";

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

    /** POST a {@link DatabaseDump}, answered by a {@link SnapshotResult}. */
    public static final String DUMP_DATABASE = "/api/backup/database";

    /** POST a {@link VolumeSnapshot}, answered by a {@link SnapshotResult}. */
    public static final String SNAPSHOT_VOLUME = "/api/backup/volume";

    /** POST an {@link UnverifiedMark}, answered by a {@link Mark}. */
    public static final String MARK_UNVERIFIED = "/api/backup/mark";

    /** POST a {@link Retention}, answered by the names it removed. */
    public static final String PRUNE = "/api/backup/prune";

    /** POST {@link Deploy}: pull, then up; answered {@code 202} with a {@link Job}. */
    public static final String DEPLOY = "/api/deploy";

    /** POST: the container again from the image on this host; answered {@code 202} with a {@link Job}. */
    public static final String RECREATE = "/api/recreate/{service}";

    /** Every job since the agent started, as {@link Job}s. */
    public static final String JOBS = "/api/jobs";

    /** One {@link Job} with its output so far. */
    public static final String JOB = JOBS + "/{id}";

    private AgentWire() {}

    /** Returns {@code route} with its one placeholder filled in, URL-safe as every caller's value already is. */
    public static String of(final String route, final String value) {
        return route.replaceFirst("\\{[a-z]+}", value);
    }

    /** Every service compose.yml defines, in file order, as its labels describe it. */
    public record Topology(List<Service> services) {}

    /**
     * One service of compose.yml.
     *
     * @param image the image reference after interpolation, or {@code null} for a service that only builds
     * @param console whether a line can be typed into it, which the label {@code eu.nordtal.console} says
     */
    public record Service(String name, @Nullable String image, boolean console) {}

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

    /** One file in the backup directory; a {@code partial} one is being written or died halfway. */
    public record Archive(String name, long bytes, String human, Instant modified, boolean partial) {}

    /** Which service runs PostgreSQL and the role pg_dump logs in as. */
    public record DatabaseDump(String service, String role) {}

    /** One volume by its Docker name, and how long the tar may take before it is abandoned. */
    public record VolumeSnapshot(String volume, Duration patience) {}

    public record UnverifiedMark(String archive, String why) {}

    /** The mark's name, or {@code null} when it could not be written. */
    public record Mark(@Nullable String name) {}

    /** The services one deployment pulls and brings up; empty means the whole project. */
    public record Deploy(List<String> services) {}

    /**
     * A deployment or a recreate, which runs on after the request that started it.
     *
     * @param state {@code RUNNING}, {@code DONE} or {@code FAILED}
     * @param lines compose's own output, filled only by {@link #JOB}
     */
    public record Job(
            String id,
            String kind,
            List<String> services,
            String state,
            Instant started,
            @Nullable Instant finished,
            @Nullable Integer exitCode,
            @Nullable List<String> lines) {}

    /** An answer that is not the one asked for, with the sentence to show and where it came from. */
    public record Refusal(String error, String where) {}
}
