package eu.nordtal.s2.steward.docker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.json.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Docker Engine API, as far as Steward needs it.
 *
 * Only {@link #stop}, {@link #start} and {@link #exec} write; creating a container is {@code steward-deployer}'s alone.
 */
public final class Docker {

    private static final Logger log = LoggerFactory.getLogger(Docker.class);

    /** Compose writes these on every container it creates; they are how a container gets a name. */
    private static final String LABEL_PROJECT = "com.docker.compose.project";

    private static final String LABEL_SERVICE = "com.docker.compose.service";

    /** How long an exec may take before the connection is closed, so a hang becomes a {@link DockerException}. */
    private static final java.time.Duration EXEC_DEADLINE = java.time.Duration.ofSeconds(15);

    private final DockerSocket socket;

    public Docker(final DockerSocket socket) {
        this.socket = socket;
    }

    public boolean isReachable() {
        return socket.isReachable();
    }

    /** Every container of one compose project, running or not. */
    public List<Container> containers(final String project) {
        final JsonArray array = Json.decode(socket.send("GET", "/containers/json?all=1", null), JsonArray.class);
        final List<Container> containers = new ArrayList<>();
        for (final JsonElement element : array) {
            final JsonObject json = element.getAsJsonObject();
            final JsonObject labels = json.has("Labels") && json.get("Labels").isJsonObject()
                    ? json.getAsJsonObject("Labels")
                    : new JsonObject();
            if (!project.equals(string(labels, LABEL_PROJECT))) {
                continue;
            }
            containers.add(new Container(
                    id(json),
                    string(labels, LABEL_SERVICE),
                    firstName(json),
                    string(json, "Image"),
                    string(json, "ImageID"),
                    string(json, "State"),
                    string(json, "Status")));
        }
        return containers;
    }

    /** One container in full: its health, when it started, and whether it has a TTY, which decides the log framing. */
    public Inspection inspect(final String id) {
        final JsonObject json = Json.decode(socket.send("GET", "/containers/" + id + "/json", null), JsonObject.class);
        final JsonObject state = json.getAsJsonObject("State");
        final JsonObject config = json.getAsJsonObject("Config");
        final String health =
                state != null && state.has("Health") ? string(state.getAsJsonObject("Health"), "Status") : null;
        final String name = string(json, "Name");
        return new Inspection(
                id(json),
                name == null ? null : name.replaceFirst("^/", ""),
                config == null ? null : string(config, "Image"),
                string(json, "Image"),
                state == null ? null : string(state, "Status"),
                health,
                state == null ? null : string(state, "StartedAt"),
                config != null && config.has("Tty") && config.get("Tty").getAsBoolean(),
                state != null && state.has("ExitCode") && !state.get("ExitCode").isJsonNull()
                        ? state.get("ExitCode").getAsInt()
                        : -1,
                repoDigests(string(json, "Image")));
    }

    /**
     * One sample of CPU and memory, which takes about a second since the daemon measures a real delta.
     *
     * Memory excludes {@code inactive_file}, as {@code docker stats} does, and its limit is the whole host's.
     */
    public Stats stats(final String id) {
        final JsonObject json =
                Json.decode(socket.send("GET", "/containers/" + id + "/stats?stream=false", null), JsonObject.class);

        final JsonObject memory = json.getAsJsonObject("memory_stats");
        long usage = 0;
        long limit = 0;
        if (memory != null) {
            usage = number(memory, "usage");
            limit = number(memory, "limit");
            final JsonObject detail =
                    memory.has("stats") && memory.get("stats").isJsonObject() ? memory.getAsJsonObject("stats") : null;
            if (detail != null && detail.has("inactive_file")) {
                usage = Math.max(0, usage - number(detail, "inactive_file"));
            }
        }
        return new Stats(usage, limit, cpuPercent(json));
    }

    /** CPU as a share of the whole machine, Docker's formula, so it exceeds 100 % on several cores. */
    private static OptionalDouble cpuPercent(final JsonObject json) {
        final JsonObject cpu = json.getAsJsonObject("cpu_stats");
        final JsonObject previous = json.getAsJsonObject("precpu_stats");
        if (cpu == null || previous == null) {
            return OptionalDouble.empty();
        }
        final JsonObject usage = cpu.getAsJsonObject("cpu_usage");
        final JsonObject previousUsage = previous.getAsJsonObject("cpu_usage");
        if (usage == null || previousUsage == null) {
            return OptionalDouble.empty();
        }
        final long containerDelta = number(usage, "total_usage") - number(previousUsage, "total_usage");
        final long systemDelta = number(cpu, "system_cpu_usage") - number(previous, "system_cpu_usage");
        if (systemDelta <= 0 || containerDelta < 0) {
            // A first reading, or a counter that went backwards; empty draws as a gap, zero would be a lie.
            return OptionalDouble.empty();
        }
        final long cpus = Math.max(1, number(cpu, "online_cpus"));
        return OptionalDouble.of(containerDelta * 100.0 / systemDelta * cpus);
    }

    /**
     * The log stream, followed or not; the caller closes it, which ends a follow.
     *
     * @param since RFC3339 or a unix timestamp; empty for everything Docker still has
     * @param tail how many lines to start with, or {@code "all"}
     */
    public DockerSocket.Stream logs(
            final String id, final boolean follow, final String tail, final @Nullable String since) {
        final StringBuilder path = new StringBuilder("/containers/")
                .append(id)
                .append("/logs?stdout=1&stderr=1&timestamps=1")
                .append("&follow=")
                .append(follow ? "1" : "0")
                .append("&tail=")
                .append(encode(tail));
        if (since != null && !since.isBlank()) {
            path.append("&since=").append(encode(since));
        }
        return socket.stream("GET", path.toString(), null);
    }

    /** The last {@code tail} lines, read to the end. Never follows. */
    public List<String> recentLines(final String id, final int tail, final boolean multiplexed) {
        final List<String> lines = new ArrayList<>();
        collect(id, tail, multiplexed, lines::add);
        return lines;
    }

    private void collect(final String id, final int tail, final boolean multiplexed, final Consumer<String> line) {
        try (DockerSocket.Stream stream = logs(id, false, String.valueOf(tail), null)) {
            LogFrames.read(stream.body(), multiplexed, line);
        } catch (IOException e) {
            throw new DockerException("reading the log of " + id, e);
        }
    }

    /**
     * The digest the registry has for this exact reference, resolved remotely by the daemon.
     *
     * Empty when that cannot be answered, which is never reported as current.
     */
    public Optional<String> registryDigest(final String imageRef) {
        try {
            final JsonObject json = Json.decode(
                    socket.send("GET", "/distribution/" + encodePath(imageRef) + "/json", null), JsonObject.class);
            final JsonObject descriptor = json.getAsJsonObject("Descriptor");
            return Optional.ofNullable(descriptor == null ? null : string(descriptor, "digest"));
        } catch (DockerException e) {
            log.debug("no registry digest for {}", imageRef, e);
            return Optional.empty();
        }
    }

    /** The digests a local image carries, as {@code repo@sha256:...}. */
    public List<String> repoDigests(final @Nullable String imageId) {
        if (imageId == null || imageId.isBlank()) {
            return List.of();
        }
        try {
            final JsonObject json =
                    Json.decode(socket.send("GET", "/images/" + encodePath(imageId) + "/json", null), JsonObject.class);
            final JsonArray digests = json.getAsJsonArray("RepoDigests");
            if (digests == null) {
                return List.of();
            }
            final List<String> all = new ArrayList<>();
            digests.forEach(element -> all.add(element.getAsString()));
            return all;
        } catch (DockerException e) {
            log.debug("no repo digests for {}", imageId, e);
            return List.of();
        }
    }

    /**
     * The daemon's own record of one local image: the digests it vouches for, and whether it was built here.
     *
     * @return empty when the image is gone from the daemon's store, as after a rebuild of its tag
     */
    public Optional<ImageIdentity> imageIdentity(final @Nullable String imageRef) {
        if (imageRef == null || imageRef.isBlank()) {
            return Optional.empty();
        }
        try {
            final JsonObject json = Json.decode(
                    socket.send("GET", "/images/" + encodePath(imageRef) + "/json", null), JsonObject.class);
            final JsonArray digests = json.getAsJsonArray("RepoDigests");
            final List<String> all = new ArrayList<>();
            if (digests != null) {
                digests.forEach(element -> all.add(element.getAsString()));
            }
            final JsonObject identity = json.getAsJsonObject("Identity");
            final boolean builtLocally = identity != null
                    && identity.has("Build")
                    && identity.get("Build").isJsonArray()
                    && !identity.getAsJsonArray("Build").isEmpty();
            return Optional.of(new ImageIdentity(all, builtLocally));
        } catch (DockerException e) {
            log.debug("no image record for {}", imageRef, e);
            return Optional.empty();
        }
    }

    public void stop(final String id, final int secondsBeforeKill) {
        socket.send("POST", "/containers/" + id + "/stop?t=" + secondsBeforeKill, null);
    }

    public void start(final String id) {
        socket.send("POST", "/containers/" + id + "/start", null);
    }

    /** Runs a command in a container from an argument list, never a shell, and collects what it printed. */
    public ExecResult exec(final String id, final List<String> command) {
        return exec(id, command, null);
    }

    /**
     * The same, as a named user; {@code pg_dump} runs as {@code postgres}, whom the image trusts on the local socket.
     */
    public ExecResult exec(final String id, final List<String> command, final @Nullable String user) {
        final JsonObject request = new JsonObject();
        request.addProperty("AttachStdout", true);
        request.addProperty("AttachStderr", true);
        request.addProperty("Tty", false);
        if (user != null && !user.isBlank()) {
            request.addProperty("User", user);
        }
        final JsonArray argv = new JsonArray();
        command.forEach(argv::add);
        request.add("Cmd", argv);

        final JsonObject created =
                Json.decode(socket.send("POST", "/containers/" + id + "/exec", Json.encode(request)), JsonObject.class);
        final String execId = string(created, "Id");
        if (execId == null) {
            throw new DockerException("docker created no exec for " + id);
        }

        final JsonObject start = new JsonObject();
        start.addProperty("Detach", false);
        start.addProperty("Tty", false);

        final StringBuilder output = new StringBuilder();
        try (DockerSocket.Stream stream =
                socket.stream("POST", "/exec/" + execId + "/start", Json.encode(start), EXEC_DEADLINE)) {
            // Always multiplexed: Tty was false above.
            LogFrames.read(stream.body(), true, line -> output.append(line).append('\n'));
        } catch (IOException e) {
            throw new DockerException("reading the answer of exec in " + id, e);
        }

        // The exit code is a second request; without it a failed command looks like a quiet one.
        final JsonObject finished =
                Json.decode(socket.send("GET", "/exec/" + execId + "/json", null), JsonObject.class);
        final int exitCode =
                finished.has("ExitCode") && !finished.get("ExitCode").isJsonNull()
                        ? finished.get("ExitCode").getAsInt()
                        : -1;
        return new ExecResult(exitCode, output.toString());
    }

    /** What a command in a container came to. An empty {@code output} with code 0 is success. */
    public record ExecResult(int exitCode, String output) {

        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** What images and volumes take up on the disk. */
    public DiskUsage diskUsage() {
        final JsonObject json = Json.decode(socket.send("GET", "/system/df", null), JsonObject.class);
        return new DiskUsage(
                sum(json, "Images", "Size"),
                sum(json, "Volumes", "UsageData", "Size"),
                sum(json, "Containers", "SizeRw"));
    }

    /** One container as the list shows it. {@code service} is null for anything not from compose. */
    public record Container(
            String id,
            @Nullable String service,
            @Nullable String name,
            @Nullable String image,
            @Nullable String imageId,
            @Nullable String state,
            @Nullable String status) {

        public boolean isRunning() {
            return "running".equalsIgnoreCase(state);
        }
    }

    /**
     * One container in full; {@code repoDigests} is what the drift check compares.
     *
     * @param exitCode what the process exited with, or {@code -1}; {@code 137} is SIGKILL after a stop timed out
     */
    public record Inspection(
            String id,
            @Nullable String name,
            @Nullable String image,
            @Nullable String imageId,
            @Nullable String state,
            @Nullable String health,
            @Nullable String startedAt,
            boolean tty,
            int exitCode,
            List<String> repoDigests) {

        /** Killed rather than asked: SIGKILL, which is what a stop that ran out of time looks like. */
        public boolean wasKilled() {
            return exitCode == 137;
        }

        /** Running, and healthy if it says anything about its health at all. */
        public boolean isBack() {
            if (!"running".equalsIgnoreCase(state)) {
                return false;
            }
            return health == null || health.isBlank() || "healthy".equalsIgnoreCase(health);
        }
    }

    /**
     * What the daemon's local image store knows about one image.
     *
     * @param repoDigests the {@code repo@sha256:...} entries recorded, non-empty for a local build too under containerd
     * @param builtLocally whether {@code Identity.Build} names at least one local build
     */
    public record ImageIdentity(List<String> repoDigests, boolean builtLocally) {}

    public record Stats(long memoryBytes, long memoryLimitBytes, OptionalDouble cpuPercent) {}

    public record DiskUsage(long imagesBytes, long volumesBytes, long containersBytes) {}

    private static @Nullable String firstName(final JsonObject json) {
        final JsonArray names = json.getAsJsonArray("Names");
        if (names == null || names.isEmpty()) {
            return null;
        }
        return names.get(0).getAsString().replaceFirst("^/", "");
    }

    /** Every container the daemon lists carries an {@code Id}; there is no shape without one. */
    private static String id(final JsonObject json) {
        return Objects.requireNonNull(string(json, "Id"), "container has no Id");
    }

    private static @Nullable String string(final @Nullable JsonObject json, final String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }

    private static long number(final @Nullable JsonObject json, final String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        return json.get(key).getAsLong();
    }

    private static long sum(final JsonObject root, final String array, final String... path) {
        final JsonArray entries = root.getAsJsonArray(array);
        if (entries == null) {
            return 0;
        }
        long total = 0;
        for (final JsonElement element : entries) {
            JsonObject cursor = element.getAsJsonObject();
            for (int i = 0; i < path.length - 1 && cursor != null; i++) {
                cursor = cursor.has(path[i]) && cursor.get(path[i]).isJsonObject()
                        ? cursor.getAsJsonObject(path[i])
                        : null;
            }
            total += number(cursor, path[path.length - 1]);
        }
        return total;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Encodes an image reference for a path segment, leaving the slashes and the colon the daemon parses. */
    private static String encodePath(final String reference) {
        return reference.replace(" ", "%20");
    }
}
