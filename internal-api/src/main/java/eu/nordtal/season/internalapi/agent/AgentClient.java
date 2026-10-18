package eu.nordtal.season.internalapi.agent;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentWire.Archive;
import eu.nordtal.season.internalapi.agent.AgentWire.Container;
import eu.nordtal.season.internalapi.agent.AgentWire.Containers;
import eu.nordtal.season.internalapi.agent.AgentWire.Host;
import eu.nordtal.season.internalapi.agent.AgentWire.Round;
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
 * Every route of {@link AgentWire}, typed.
 * It is the only way that steward reaches Docker and the volumes.
 * It stops no container.
 */
public final class AgentClient {

    /** The time allowed for the registry comparison. */
    private static final Duration IMAGES_WITHIN = Duration.ofMinutes(2);

    /** A resolve asks every source once. */
    private static final Duration PLAN_WITHIN = Duration.ofMinutes(2);

    /** {@code du} on a large world. The agent stops it after half a minute. */
    private static final Duration DISK_WITHIN = Duration.ofSeconds(40);

    private final InternalClient http;

    public AgentClient(final InternalClient http) {
        this.http = http;
    }

    public boolean isReachable() {
        return http.isReachable();
    }

    public AgentWire.Topology topology() {
        return Json.decode(http.get(AgentWire.TOPOLOGY), AgentWire.Topology.class);
    }

    public Containers containers() {
        return Json.decode(http.get(AgentWire.CONTAINERS), Containers.class);
    }

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

    public List<Round> samples(final @Nullable Instant after) {
        final String query = after == null ? "" : "?after=" + encode(after.toString());
        return Json.decode(http.get(AgentWire.SAMPLES + query), new TypeToken<List<Round>>() {});
    }

    /** Returns the size of the volume of a service by {@code du}, or empty if it has none or could not be measured. */
    public OptionalLong disk(final String service) {
        final Long bytes = Json.decode(
                        http.get(AgentWire.of(AgentWire.DISK, service), DISK_WITHIN), AgentWire.Disk.class)
                .bytes();
        return bytes == null ? OptionalLong.empty() : OptionalLong.of(bytes);
    }

    public List<AgentWire.BundleRef> bundles() {
        return Json.decode(http.get(AgentWire.BUNDLES), new TypeToken<List<AgentWire.BundleRef>>() {});
    }

    public List<AgentWire.Descriptor> descriptors() {
        return Json.decode(http.get(AgentWire.DESCRIPTORS), new TypeToken<List<AgentWire.Descriptor>>() {});
    }

    public MessageBundle bundle(final String service, final String module) {
        return Json.decode(http.get(bundlePath(service, module)), MessageBundle.class);
    }

    private static String bundlePath(final String service, final String module) {
        return AgentWire.of(AgentWire.BUNDLE, encode(service)) + (module.isEmpty() ? "" : "?module=" + encode(module));
    }

    public List<Archive> archives() {
        return Json.decode(http.get(AgentWire.BACKUPS), new TypeToken<List<Archive>>() {});
    }

    /** Returns the bytes of one finished archive as they arrive. The caller closes the stream. */
    public InputStream archive(final String name) {
        return http.stream(AgentWire.of(AgentWire.BACKUP, encode(name)), "application/octet-stream");
    }

    public int logCapacity(final String service, final int max) {
        return Json.decode(
                        http.get(AgentWire.of(AgentWire.LOG_CAPACITY, service) + "?max=" + max),
                        AgentWire.LogCapacity.class)
                .lines();
    }

    /**
     * Opens the log of one service as events, the backlog first. The caller reads it on its own thread and closes it.
     *
     * @param tail how many lines of backlog, or {@code all}
     * @param since an instant or a Docker duration, or {@code null} for no lower bound
     */
    public LogFollow logs(final String service, final String tail, final @Nullable String since) {
        final String query = "?tail=" + encode(tail) + (since == null ? "" : "&since=" + encode(since));
        return new LogFollow(http.stream(AgentWire.of(AgentWire.LOGS, service) + query, "text/event-stream"));
    }

    /**
     * Types one line into a server's console. The answer appears in that server's log.
     *
     * @param actor who typed it
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
                .map(container -> new ServiceRuntime(
                        container.service(),
                        container.id(),
                        container.state(),
                        container.health(),
                        container.restarts()))
                .toList());
    }

    public ImageResult images() {
        try {
            return Json.decode(http.get(AgentWire.IMAGES, IMAGES_WITHIN), ImageResult.class);
        } catch (final InternalClient.Failure unreachable) {
            return ImageResult.unreachable(sentence(unreachable));
        }
    }

    public AgentWire.Resolve plan() {
        return Json.decode(http.get(AgentWire.PLAN, PLAN_WITHIN), AgentWire.Resolve.class);
    }

    public AgentWire.Plugins plugins(final String service) {
        return Json.decode(http.get(AgentWire.of(AgentWire.PLUGINS, encode(service))), AgentWire.Plugins.class);
    }

    /** Modrinth's hits for {@code query} on one server. */
    public AgentWire.PluginSearch searchPlugins(final String service, final String query) {
        return Json.decode(
                http.get(AgentWire.of(AgentWire.PLUGIN_SEARCH, encode(service)) + "?q=" + encode(query)),
                AgentWire.PluginSearch.class);
    }

    /** Adds a plugin to one server. The next update run installs it. */
    public AgentWire.PluginAdded addPlugin(final String service, final AgentWire.AddPlugin plugin) {
        return Json.decode(
                http.post(AgentWire.of(AgentWire.PLUGINS, encode(service)), Json.encode(plugin)),
                AgentWire.PluginAdded.class);
    }

    /** Returns the sentence of the failure: the agent's own words, or else the client's. */
    public static String sentence(final InternalClient.Failure failure) {
        return refusal(failure).map(AgentWire.Refusal::error).orElseGet(() -> {
            final String body = failure.body();
            return body == null || body.isBlank()
                    ? String.valueOf(failure.getMessage())
                    : failure.getMessage() + ": " + body;
        });
    }

    public static Optional<AgentWire.Refusal> refusal(final InternalClient.Failure failure) {
        final String body = failure.body();
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            final AgentWire.Refusal refusal = Json.decode(body, AgentWire.Refusal.class);
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
