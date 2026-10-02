package eu.nordtal.s2.internalapi.agent;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.agent.AgentWire.Archive;
import eu.nordtal.s2.internalapi.agent.AgentWire.Container;
import eu.nordtal.s2.internalapi.agent.AgentWire.Containers;
import eu.nordtal.s2.internalapi.agent.AgentWire.Host;
import eu.nordtal.s2.internalapi.agent.AgentWire.Round;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * The only way steward reaches Docker and the volumes: every route of {@link AgentWire}, typed.
 *
 * It reads and asks; nothing on it stops a container, since every stop is a run the agent carries out.
 */
public final class AgentClient {

    /** How long the registry comparison may take, one question per image. */
    private static final Duration IMAGES_WITHIN = Duration.ofMinutes(2);

    /** A resolve asks every source once; GitHub and Modrinth together can take this long. */
    private static final Duration PLAN_WITHIN = Duration.ofMinutes(2);

    /** {@code du} on a large world; the agent stops it after half a minute. */
    private static final Duration DISK_WITHIN = Duration.ofSeconds(40);

    private final InternalClient http;

    /** Talks to steward-agent through {@code http}. */
    public AgentClient(final InternalClient http) {
        this.http = http;
    }

    /** Whether the agent answers its health route at all. */
    public boolean isReachable() {
        return http.isReachable();
    }

    public AgentWire.Topology topology() {
        return Json.decode(http.get(AgentWire.TOPOLOGY), AgentWire.Topology.class);
    }

    /** Every container of the project with its state and its last sample. */
    public Containers containers() {
        return Json.decode(http.get(AgentWire.CONTAINERS), Containers.class);
    }

    /** One service's container with its image's digests, or empty when it has none. */
    public Optional<Container> container(final String service) {
        try {
            return Optional.of(Json.decode(http.get(AgentWire.of(AgentWire.CONTAINER, service)), Container.class));
        } catch (final InternalClient.Failure missing) {
            if (missing.status() == 404) {
                return Optional.empty();
            }
            throw missing;
        }
    }

    public Host host() {
        return Json.decode(http.get(AgentWire.HOST), Host.class);
    }

    /** The sampler's rounds taken after {@code after}, oldest first; {@code null} asks for every round it holds. */
    public List<Round> samples(final @Nullable Instant after) {
        final String query = after == null ? "" : "?after=" + encode(after.toString());
        return Json.decode(http.get(AgentWire.SAMPLES + query), new TypeToken<List<Round>>() {});
    }

    /** One service's volume size by {@code du}, empty when it has no volume or could not be measured. */
    public OptionalLong disk(final String service) {
        final Long bytes = Json.decode(
                        http.get(AgentWire.of(AgentWire.DISK, service), DISK_WITHIN), AgentWire.Disk.class)
                .bytes();
        return bytes == null ? OptionalLong.empty() : OptionalLong.of(bytes);
    }

    public List<AgentWire.BundleRef> bundles() {
        return Json.decode(http.get(AgentWire.BUNDLES), new TypeToken<List<AgentWire.BundleRef>>() {});
    }

    /** One bundle, its packaged text and overrides side by side; a {@code 404} failure for one that is not there. */
    public MessageBundle bundle(final String service, final String module) {
        return Json.decode(http.get(bundlePath(service, module)), MessageBundle.class);
    }

    /** Saves overrides; a {@code 400} failure says which placeholder a text may not use, and nothing is saved. */
    public AgentWire.SavedBundle saveBundle(
            final String service, final String module, final List<AgentWire.TextChange> changes) {
        return Json.decode(
                http.post(bundlePath(service, module), Json.encode(new AgentWire.BundleChanges(changes))),
                AgentWire.SavedBundle.class);
    }

    private static String bundlePath(final String service, final String module) {
        return AgentWire.of(AgentWire.BUNDLE, encode(service)) + (module.isEmpty() ? "" : "?module=" + encode(module));
    }

    public List<Archive> archives() {
        return Json.decode(http.get(AgentWire.BACKUPS), new TypeToken<List<Archive>>() {});
    }

    /** One finished archive's bytes as they arrive; the caller closes the stream. */
    public InputStream archive(final String name) {
        return http.stream(AgentWire.of(AgentWire.BACKUP, encode(name)), "application/octet-stream");
    }

    /** How many lines one service's console can fill, Docker's and the archive's, at most {@code max}. */
    public int logCapacity(final String service, final int max) {
        return Json.decode(
                        http.get(AgentWire.of(AgentWire.LOG_CAPACITY, service) + "?max=" + max),
                        AgentWire.LogCapacity.class)
                .lines();
    }

    /**
     * Opens one service's log as events, the backlog first; the caller reads it on its own thread and closes it.
     *
     * @param tail how many lines of backlog, or {@code all}
     * @param since an instant or a Docker duration, or {@code null} for no lower bound
     */
    public LogFollow logs(final String service, final String tail, final @Nullable String since) {
        final String query = "?tail=" + encode(tail) + (since == null ? "" : "&since=" + encode(since));
        return new LogFollow(http.stream(AgentWire.of(AgentWire.LOGS, service) + query, "text/event-stream"));
    }

    /**
     * Types one line into a server's console; the answer appears in that server's log.
     *
     * @param actor who typed it, as the journal names them
     */
    public void console(final String service, final String command, final Actor actor) {
        http.post(AgentWire.of(AgentWire.CONSOLE, service), Json.encode(new AgentWire.ConsoleLine(command, actor)));
    }

    public RuntimeResult runtime() {
        final Containers all;
        try {
            all = containers();
        } catch (final InternalClient.Failure unreachable) {
            return RuntimeResult.unreachable(sentence(unreachable));
        }
        if (!all.reached()) {
            return RuntimeResult.unreachable(String.valueOf(all.message()));
        }
        return RuntimeResult.of(all.containers().stream()
                .map(container ->
                        new ServiceRuntime(container.service(), container.id(), container.state(), container.health()))
                .toList());
    }

    public ImageResult images() {
        try {
            return Json.decode(http.get(AgentWire.IMAGES, IMAGES_WITHIN), ImageResult.class);
        } catch (final InternalClient.Failure unreachable) {
            return ImageResult.unreachable(sentence(unreachable));
        }
    }

    /** The resolve as the updates page draws it. */
    public AgentWire.Resolve plan() {
        return Json.decode(http.get(AgentWire.PLAN, PLAN_WITHIN), AgentWire.Resolve.class);
    }

    /** One server's plugins. */
    public AgentWire.Plugins plugins(final String service) {
        return Json.decode(http.get(AgentWire.of(AgentWire.PLUGINS, encode(service))), AgentWire.Plugins.class);
    }

    /** Modrinth's hits for {@code query} on one server. */
    public AgentWire.PluginSearch searchPlugins(final String service, final String query) {
        return Json.decode(
                http.get(AgentWire.of(AgentWire.PLUGIN_SEARCH, encode(service)) + "?q=" + encode(query)),
                AgentWire.PluginSearch.class);
    }

    /** Adds a plugin to one server; it is installed by the next update run. */
    public AgentWire.PluginAdded addPlugin(final String service, final AgentWire.AddPlugin plugin) {
        return Json.decode(
                http.post(AgentWire.of(AgentWire.PLUGINS, encode(service)), Json.encode(plugin)),
                AgentWire.PluginAdded.class);
    }

    /** The failure's sentence: what the agent itself said when it said anything, the client's own words if not. */
    public static String sentence(final InternalClient.Failure failure) {
        return refusal(failure).map(AgentWire.Refusal::error).orElseGet(() -> {
            final String body = failure.body();
            return body == null || body.isBlank()
                    ? String.valueOf(failure.getMessage())
                    : failure.getMessage() + ": " + body;
        });
    }

    /** The agent's own refusal inside a failure, when its body is one. */
    public static Optional<AgentWire.Refusal> refusal(final InternalClient.Failure failure) {
        final String body = failure.body();
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            final AgentWire.Refusal refusal = Json.decode(body, AgentWire.Refusal.class);
            // Gson leaves a missing field null whatever the record declares.
            return refusal != null && refusal.error() != null && refusal.where() != null
                    ? Optional.of(refusal)
                    : Optional.empty();
        } catch (final JsonParseException notJson) {
            return Optional.empty();
        }
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
