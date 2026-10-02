package eu.nordtal.s2.internalapi.agent;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.agent.AgentWire.Archive;
import eu.nordtal.s2.internalapi.agent.AgentWire.Container;
import eu.nordtal.s2.internalapi.agent.AgentWire.Containers;
import eu.nordtal.s2.internalapi.agent.AgentWire.Host;
import eu.nordtal.s2.internalapi.agent.AgentWire.Job;
import eu.nordtal.s2.internalapi.agent.AgentWire.Round;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The only way steward reaches Docker and the volumes: every route of {@link AgentWire}, typed.
 *
 * As {@link ContainerOps} it is what a run stops and starts through; {@link #snapshots} is what a backup saves with.
 */
public final class AgentClient implements ContainerOps {

    /** How often a deployment's job is asked about. */
    private static final Duration JOB_POLL = Duration.ofSeconds(3);

    /** How long the registry comparison may take, one question per image. */
    private static final Duration IMAGES_WITHIN = Duration.ofMinutes(2);

    /** How long a stop may take: Docker's grace period and the inspect after it. */
    private static final Duration STOP_WITHIN = Duration.ofSeconds(60);

    /** How long a database dump may take. */
    private static final Duration DUMP_WITHIN = Duration.ofMinutes(30);

    private final InternalClient http;
    private final Waiting waiting;
    private final Duration jobPatience;

    /**
     * Talks to steward-agent through {@code http}.
     *
     * @param jobPatience how long a deployment or a recreate may run before a run stops waiting for it
     */
    public AgentClient(final InternalClient http, final Waiting waiting, final Duration jobPatience) {
        this.http = http;
        this.waiting = waiting;
        this.jobPatience = jobPatience;
    }

    /** Whether the agent answers its health route at all. */
    public boolean isReachable() {
        return http.isReachable();
    }

    /** What compose.yml's labels say about every service. */
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
    public void console(final String service, final String command, final String actor) {
        http.post(AgentWire.of(AgentWire.CONSOLE, service), Json.encode(new AgentWire.ConsoleLine(command, actor)));
    }

    public List<Job> jobs() {
        return Json.decode(http.get(AgentWire.JOBS), new TypeToken<List<Job>>() {});
    }

    public Job job(final String id) {
        return Json.decode(http.get(AgentWire.of(AgentWire.JOB, encode(id))), Job.class);
    }

    /** Starts recreating one service from the image on this host and answers with the job, without waiting. */
    public Job startRecreate(final String service) {
        return Json.decode(http.post(AgentWire.of(AgentWire.RECREATE, service), "{}"), Job.class);
    }

    @Override
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

    @Override
    public RedeployResult stop(final String containerId) {
        return acted("stopping", AgentWire.of(AgentWire.STOP, containerId), STOP_WITHIN);
    }

    @Override
    public RedeployResult start(final String containerId) {
        return acted("starting", AgentWire.of(AgentWire.START, containerId), STOP_WITHIN);
    }

    /** Sends a stop or a start; an agent that did not answer is a refusal, since nothing is known to have happened. */
    private RedeployResult acted(final String verb, final String path, final Duration within) {
        try {
            return Json.decode(http.post(path, "{}", within), RedeployResult.class);
        } catch (final InternalClient.Failure refused) {
            return RedeployResult.refused(verb + ": " + sentence(refused));
        }
    }

    @Override
    public ImageResult images() {
        try {
            return Json.decode(http.get(AgentWire.IMAGES, IMAGES_WITHIN), ImageResult.class);
        } catch (final InternalClient.Failure unreachable) {
            return ImageResult.unreachable(sentence(unreachable));
        }
    }

    /** Pulls one service's image and recreates it, waiting for the job; the service is always named. */
    @Override
    public RedeployResult deploy(final String service) {
        return followed(
                service,
                "deploy",
                () -> http.post(AgentWire.DEPLOY, Json.encode(new AgentWire.Deploy(List.of(service)))));
    }

    /** Recreates one service from the image already here, waiting for the job. */
    @Override
    public RedeployResult recreate(final String service) {
        return followed(service, "recreate", () -> http.post(AgentWire.of(AgentWire.RECREATE, service), "{}"));
    }

    /**
     * Sends one of the two requests and follows the job it hands back until it settles or the patience runs out.
     *
     * @param what "deploy" or "recreate", named in every message since only a deploy fetches an image
     */
    private RedeployResult followed(final String service, final String what, final Supplier<String> send) {
        final Instant deadline = waiting.now().plus(jobPatience);
        final Job accepted;
        try {
            accepted = Json.decode(send.get(), Job.class);
        } catch (final InternalClient.Failure refused) {
            return RedeployResult.refused("the " + what + " of " + service + " was not accepted: " + sentence(refused));
        } catch (final JsonParseException malformed) {
            return RedeployResult.unverified(AgentWire.SERVICE + " accepted the " + what + " of " + service
                    + " but named no job to follow: " + malformed.getMessage());
        }
        final String job = " (job " + accepted.id() + ")";
        while (true) {
            final Job state;
            try {
                state = job(accepted.id());
            } catch (final InternalClient.Failure unread) {
                return RedeployResult.unverified(AgentWire.SERVICE + " accepted the " + what + " of " + service + job
                        + ", and how it went could not be read back: " + sentence(unread));
            }
            if ("DONE".equals(state.state())) {
                return RedeployResult.triggered(AgentWire.SERVICE + " finished the " + what + " of " + service + job);
            }
            if ("FAILED".equals(state.state())) {
                return RedeployResult.refused(
                        AgentWire.SERVICE + "'s " + what + " of " + service + " failed" + job + ": " + lastLine(state));
            }
            if (!waiting.now().isBefore(deadline)) {
                return RedeployResult.unverified(AgentWire.SERVICE + "'s " + what + " of " + service + job
                        + " had not finished after " + jobPatience.toSeconds() + "s; it may still be running, and"
                        + " Steward's agent page shows it");
            }
            if (!waiting.sleep(JOB_POLL)) {
                return RedeployResult.unverified(
                        "interrupted while waiting for " + AgentWire.SERVICE + "'s " + what + " of " + service + job);
            }
        }
    }

    private static String lastLine(final Job job) {
        final List<String> lines = job.lines();
        return lines == null || lines.isEmpty() ? "no output" : lines.getLast();
    }

    /**
     * What a backup saves through, with the database and the patience read again for every call.
     *
     * @param plan which service runs PostgreSQL (blank for no dump), its backup role, and one volume's patience
     */
    public Snapshots snapshots(final Supplier<Backup> plan) {
        return new AgentSnapshots(this, plan);
    }

    /**
     * What a backup is told on every call.
     *
     * @param databaseService the compose service running PostgreSQL, or blank to dump nothing
     */
    public record Backup(String databaseService, String role, Duration patience) {}

    SnapshotResult dumpDatabase(final String service, final String role) {
        return saved(
                Snapshots.DATABASE,
                AgentWire.DUMP_DATABASE,
                Json.encode(new AgentWire.DatabaseDump(service, role)),
                DUMP_WITHIN);
    }

    SnapshotResult snapshot(final String volume, final Duration patience) {
        return saved(
                volume,
                AgentWire.SNAPSHOT_VOLUME,
                Json.encode(new AgentWire.VolumeSnapshot(volume, patience)),
                patience.plus(Duration.ofMinutes(1)));
    }

    /** Sends one save, and turns an agent that did not answer into a failed save of that name. */
    private SnapshotResult saved(final String name, final String path, final String body, final Duration within) {
        try {
            return Json.decode(http.post(path, body, within), SnapshotResult.class);
        } catch (final InternalClient.Failure failed) {
            return SnapshotResult.failed(name, Duration.ZERO, sentence(failed));
        }
    }

    @Nullable
    String mark(final String archive, final String why) {
        try {
            return Json.decode(
                            http.post(
                                    AgentWire.MARK_UNVERIFIED, Json.encode(new AgentWire.UnverifiedMark(archive, why))),
                            AgentWire.Mark.class)
                    .name();
        } catch (final InternalClient.Failure failed) {
            return null;
        }
    }

    List<String> prune(final Retention policy) {
        return Json.decode(http.post(AgentWire.PRUNE, Json.encode(policy)), new TypeToken<List<String>>() {});
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
