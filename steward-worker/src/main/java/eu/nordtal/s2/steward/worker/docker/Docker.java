package eu.nordtal.s2.steward.worker.docker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * The Docker Engine API, as far as Steward needs it.
 *
 * <p>Everything here is a read except {@link #stop}, {@link #start} and {@link #exec}. Creating a
 * container is not here at all and never will be: that is {@code steward-deployer}'s, because it
 * needs the compose file to do it correctly, and two services able to create containers is one
 * too many (§8b).</p>
 */
public final class Docker {

    private static final Logger log = LoggerFactory.getLogger(Docker.class);
    private static final Gson GSON = new Gson();

    /** Compose writes these on every container it creates; they are how a container gets a name. */
    private static final String LABEL_PROJECT = "com.docker.compose.project";
    private static final String LABEL_SERVICE = "com.docker.compose.service";

    /**
     * How long an exec may take before the connection is closed under it.
     *
     * <p>Generous for something that should answer in milliseconds, and finite because the caller
     * is a person waiting for a console to respond. A closed channel surfaces as a
     * {@link DockerException}, which is an answer; a hang is not.</p>
     */
    private static final java.time.Duration EXEC_DEADLINE = java.time.Duration.ofSeconds(15);

    private final DockerSocket socket;

    public Docker(final @NotNull DockerSocket socket) {
        this.socket = socket;
    }

    public boolean isReachable() {
        return socket.isReachable();
    }

    /** Every container of one compose project, running or not. */
    public @NotNull List<Container> containers(final @NotNull String project) {
        final JsonArray array = GSON.fromJson(
                socket.send("GET", "/containers/json?all=1", null), JsonArray.class);
        final List<Container> containers = new ArrayList<>();
        for (final JsonElement element : array) {
            final JsonObject json = element.getAsJsonObject();
            final JsonObject labels = json.has("Labels") && json.get("Labels").isJsonObject()
                    ? json.getAsJsonObject("Labels") : new JsonObject();
            if (!project.equals(string(labels, LABEL_PROJECT))) {
                continue;
            }
            containers.add(new Container(
                    string(json, "Id"),
                    string(labels, LABEL_SERVICE),
                    firstName(json),
                    string(json, "Image"),
                    string(json, "ImageID"),
                    string(json, "State"),
                    string(json, "Status")));
        }
        return containers;
    }

    /**
     * One container in full: its health, when it started, and whether it has a TTY.
     *
     * <p>The TTY is not a detail. It decides how the log stream is framed, and reading the wrong
     * framing produces output that is subtly wrong rather than obviously broken - see
     * {@link LogFrames}.</p>
     */
    public @NotNull Inspection inspect(final @NotNull String id) {
        final JsonObject json = GSON.fromJson(
                socket.send("GET", "/containers/" + id + "/json", null), JsonObject.class);
        final JsonObject state = json.getAsJsonObject("State");
        final JsonObject config = json.getAsJsonObject("Config");
        final String health = state != null && state.has("Health")
                ? string(state.getAsJsonObject("Health"), "Status") : null;
        return new Inspection(
                string(json, "Id"),
                json.has("Name") ? string(json, "Name").replaceFirst("^/", "") : null,
                config == null ? null : string(config, "Image"),
                string(json, "Image"),
                state == null ? null : string(state, "Status"),
                health,
                state == null ? null : string(state, "StartedAt"),
                config != null && config.has("Tty") && config.get("Tty").getAsBoolean(),
                repoDigests(string(json, "Image")));
    }

    /**
     * One sample of CPU and memory.
     *
     * <p><b>{@code one-shot} is deliberately not set.</b> With it, Docker answers immediately and
     * leaves {@code precpu_stats} at zero, which makes every CPU figure either meaningless or
     * 100 %. Without it the daemon takes two readings about a second apart and the delta is real -
     * so this call costs a second of wall clock, which is why the sampler runs it on its own
     * thread and not inside a request.</p>
     *
     * <p>Memory is Docker's own definition: usage minus {@code inactive_file}, the same arithmetic
     * {@code docker stats} prints, because page cache that the kernel will drop under pressure is
     * not memory the service is using. <b>The limit is the host's memory</b> unless a container has
     * one of its own, and none of ours does (measured 2026-09-12) - so a percentage here is a share
     * of the whole machine and has to be labelled that way (§10c).</p>
     */
    public @NotNull Stats stats(final @NotNull String id) {
        final JsonObject json = GSON.fromJson(
                socket.send("GET", "/containers/" + id + "/stats?stream=false", null),
                JsonObject.class);

        final JsonObject memory = json.getAsJsonObject("memory_stats");
        long usage = 0;
        long limit = 0;
        if (memory != null) {
            usage = number(memory, "usage");
            limit = number(memory, "limit");
            final JsonObject detail = memory.has("stats") && memory.get("stats").isJsonObject()
                    ? memory.getAsJsonObject("stats") : null;
            if (detail != null && detail.has("inactive_file")) {
                usage = Math.max(0, usage - number(detail, "inactive_file"));
            }
        }
        return new Stats(usage, limit, cpuPercent(json));
    }

    /**
     * CPU as a share of the whole machine.
     *
     * <p>Docker's own formula: the container's delta over the system delta, times the number of
     * CPUs. It can exceed 100 % on a multi-core host and that is correct - one container using two
     * cores fully is 200 % in {@code docker stats} as well.</p>
     */
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
            // The first reading of a container, or a counter that went backwards after a restart.
            // Empty says "not known", which a chart can draw as a gap; zero would be a lie.
            return OptionalDouble.empty();
        }
        final long cpus = Math.max(1, number(cpu, "online_cpus"));
        return OptionalDouble.of(containerDelta * 100.0 / systemDelta * cpus);
    }

    /**
     * The log stream, followed or not.
     *
     * <p>The caller closes the returned stream, and closing it is how a follow is ended. Docker's
     * own rotation is the only retention there is: {@code json-file} with {@code max-size 10m} and
     * {@code max-file 5} measured on this host on 2026-09-12, so <b>up to 50 MB per container and
     * not one byte more</b>. What it has rotated away is gone, and recreating a container starts
     * the buffer again - both of which the interface has to say out loud rather than imply.</p>
     *
     * @param since RFC3339 or a unix timestamp; empty for everything Docker still has
     * @param tail  how many lines to start with, or {@code "all"}
     */
    public @NotNull DockerSocket.Stream logs(final @NotNull String id, final boolean follow,
                                             final @NotNull String tail, final @Nullable String since) {
        final StringBuilder path = new StringBuilder("/containers/").append(id)
                .append("/logs?stdout=1&stderr=1&timestamps=1")
                .append("&follow=").append(follow ? "1" : "0")
                .append("&tail=").append(encode(tail));
        if (since != null && !since.isBlank()) {
            path.append("&since=").append(encode(since));
        }
        return socket.stream("GET", path.toString(), null);
    }

    /** The last {@code tail} lines, read to the end and handed back. Never follows. */
    public @NotNull List<String> recentLines(final @NotNull String id, final int tail,
                                             final boolean multiplexed) {
        final List<String> lines = new ArrayList<>();
        collect(id, tail, multiplexed, lines::add);
        return lines;
    }

    private void collect(final String id, final int tail, final boolean multiplexed,
                         final Consumer<String> line) {
        try (DockerSocket.Stream stream = logs(id, false, String.valueOf(tail), null)) {
            LogFrames.read(stream.body(), multiplexed, line);
        } catch (IOException e) {
            throw new DockerException("reading the log of " + id, e);
        }
    }

    /**
     * What the registry has for this exact reference, as a digest.
     *
     * <p>This is the check Arcane did not do. Its image comparison never asked a registry at all,
     * so four releases ran behind while the interface said "up to date" - {@code todo.md} A24. Here
     * the daemon is asked to resolve the reference remotely, and the answer is compared with the
     * digest the running container was created from.</p>
     *
     * <p>Empty means the question could not be answered - a private registry, no credentials, no
     * network. It is never reported as "current": not knowing and being current are different
     * answers and the one that gets conflated is the one that costs four releases.</p>
     */
    public @NotNull Optional<String> registryDigest(final @NotNull String imageRef) {
        try {
            final JsonObject json = GSON.fromJson(
                    socket.send("GET", "/distribution/" + encodePath(imageRef) + "/json", null),
                    JsonObject.class);
            final JsonObject descriptor = json.getAsJsonObject("Descriptor");
            return Optional.ofNullable(descriptor == null ? null : string(descriptor, "digest"));
        } catch (DockerException e) {
            log.debug("no registry digest for {}", imageRef, e);
            return Optional.empty();
        }
    }

    /** The digests a local image carries, as {@code repo@sha256:...}. */
    public @NotNull List<String> repoDigests(final @NotNull String imageId) {
        if (imageId == null || imageId.isBlank()) {
            return List.of();
        }
        try {
            final JsonObject json = GSON.fromJson(
                    socket.send("GET", "/images/" + encodePath(imageId) + "/json", null),
                    JsonObject.class);
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

    public void stop(final @NotNull String id, final int secondsBeforeKill) {
        socket.send("POST", "/containers/" + id + "/stop?t=" + secondsBeforeKill, null);
    }

    public void start(final @NotNull String id) {
        socket.send("POST", "/containers/" + id + "/start", null);
    }

    /**
     * Runs a command in a container and collects what it printed.
     *
     * <p>This is the console of §10a.2, and it is deliberately not a shell: the caller passes an
     * argument list, nothing is concatenated, and there is no interpretation of quotes or
     * semicolons anywhere on the way. <b>Only the four Minecraft services get this offered</b>, and
     * that rule lives above this method - here it would be the wrong place, because a
     * general-purpose exec that quietly refuses some containers is a puzzle rather than a
     * boundary.</p>
     */
    public @NotNull ExecResult exec(final @NotNull String id, final @NotNull List<String> command) {
        return exec(id, command, null);
    }

    /**
     * The same, as a named user inside the container.
     *
     * <p>Needed for exactly one thing so far: {@code pg_dump} has to run as {@code postgres},
     * because the official image trusts the local socket for that user and for nobody else. Passing
     * a user is a smaller lie than putting a password in an argument list where {@code ps} can read
     * it.</p>
     */
    public @NotNull ExecResult exec(final @NotNull String id, final @NotNull List<String> command,
                                    final @Nullable String user) {
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

        final JsonObject created = GSON.fromJson(
                socket.send("POST", "/containers/" + id + "/exec", GSON.toJson(request)),
                JsonObject.class);
        final String execId = string(created, "Id");
        if (execId == null) {
            throw new DockerException("docker created no exec for " + id);
        }

        final JsonObject start = new JsonObject();
        start.addProperty("Detach", false);
        start.addProperty("Tty", false);

        final StringBuilder output = new StringBuilder();
        try (DockerSocket.Stream stream = socket.stream(
                "POST", "/exec/" + execId + "/start", GSON.toJson(start), EXEC_DEADLINE)) {
            // Always multiplexed: Tty was false above, so the answer carries frame headers even
            // though the container it runs in may have a TTY of its own.
            LogFrames.read(stream.body(), true, line -> output.append(line).append('\n'));
        } catch (IOException e) {
            throw new DockerException("reading the answer of exec in " + id, e);
        }

        // THE EXIT CODE IS A SECOND REQUEST, and leaving it out is how a failed command looks like
        // a quiet one. `pg_dump` writes its complaint to stderr and exits 1; without this the
        // caller would see some text, no exception, and would go on to rename a partial file over
        // a good backup.
        final JsonObject finished = GSON.fromJson(
                socket.send("GET", "/exec/" + execId + "/json", null), JsonObject.class);
        final int exitCode = finished.has("ExitCode") && !finished.get("ExitCode").isJsonNull()
                ? finished.get("ExitCode").getAsInt() : -1;
        return new ExecResult(exitCode, output.toString());
    }

    /** What a command in a container came to. An empty {@code output} with code 0 is success. */
    public record ExecResult(int exitCode, @NotNull String output) {

        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** What images and volumes take up on the disk - the second half of "how full is the box". */
    public @NotNull DiskUsage diskUsage() {
        final JsonObject json = GSON.fromJson(socket.send("GET", "/system/df", null), JsonObject.class);
        return new DiskUsage(
                sum(json, "Images", "Size"),
                sum(json, "Volumes", "UsageData", "Size"),
                sum(json, "Containers", "SizeRw"));
    }

    // --- the shapes ------------------------------------------------------------------------

    /** One container as the list shows it. {@code service} is null for anything not from compose. */
    public record Container(@NotNull String id, @Nullable String service, @Nullable String name,
                            @Nullable String image, @Nullable String imageId,
                            @Nullable String state, @Nullable String status) {

        public boolean isRunning() {
            return "running".equalsIgnoreCase(state);
        }
    }

    /** One container in full. {@code repoDigests} is what the drift check compares. */
    public record Inspection(@NotNull String id, @Nullable String name, @Nullable String image,
                             @Nullable String imageId, @Nullable String state,
                             @Nullable String health, @Nullable String startedAt, boolean tty,
                             @NotNull List<String> repoDigests) {

        /** Running, and healthy if it says anything about its health at all. */
        public boolean isBack() {
            if (!"running".equalsIgnoreCase(state)) {
                return false;
            }
            return health == null || health.isBlank() || "healthy".equalsIgnoreCase(health);
        }
    }

    public record Stats(long memoryBytes, long memoryLimitBytes, @NotNull OptionalDouble cpuPercent) { }

    public record DiskUsage(long imagesBytes, long volumesBytes, long containersBytes) { }

    // --- the small print -------------------------------------------------------------------

    private static String firstName(final JsonObject json) {
        final JsonArray names = json.getAsJsonArray("Names");
        if (names == null || names.isEmpty()) {
            return null;
        }
        return names.get(0).getAsString().replaceFirst("^/", "");
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
                        ? cursor.getAsJsonObject(path[i]) : null;
            }
            total += number(cursor, path[path.length - 1]);
        }
        return total;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Encodes an image reference for a path segment.
     *
     * <p>{@code ghcr.io/nordtal/smp:latest} has to stay readable to the daemon, so the slashes and
     * the colon are left alone and only what would break the request line is escaped.</p>
     */
    private static String encodePath(final String reference) {
        // The slashes and the colon are what the daemon parses the reference WITH, so percent-
        // encoding them - which is what URLEncoder would do - turns a valid reference into a 404.
        // A space cannot occur in a valid one; it is escaped anyway so a malformed value produces
        // an error from Docker rather than a broken request line.
        return reference.replace(" ", "%20");
    }
}
