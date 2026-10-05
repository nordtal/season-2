package eu.nordtal.season.stewardagent.logs;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.sse.Follows;
import eu.nordtal.season.stewardagent.docker.Docker;
import eu.nordtal.season.stewardagent.docker.DockerSocket;
import eu.nordtal.season.stewardagent.docker.LogFrames;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One service's log as events: what Docker holds, the server's rotated logs before it, then every new line.
 *
 * The backlog reaches past the container into earlier runs only when Docker alone cannot fill the window.
 */
public final class LogStreams implements AutoCloseable {

    /** The console's steps are 1000, 5000 and 10000 lines; counting past the top one buys nothing. */
    private static final int CAPACITY_MAX = 10_000;

    private final Docker docker;
    private final String project;
    private final LogArchive archive;
    private final Clock clock;
    private final Follows follows;

    /** @param volumesRoot where the Minecraft data volumes are mounted, one directory per service */
    public LogStreams(
            final Docker docker,
            final String project,
            final @Nullable Path volumesRoot,
            final Clock clock,
            final Scheduler scheduler) {
        this.docker = docker;
        this.project = project;
        this.archive = new LogArchive(volumesRoot, clock::instant);
        this.clock = clock;
        this.follows = new Follows(AgentWire.SERVICE, scheduler);
    }

    /** {@link AgentWire#LOGS}: the backlog first, then the live log, until steward hangs up. */
    public void follow(final SseClient client) {
        final String service = client.ctx().pathParam("service");
        final String containerId = docker.running(project, service).orElse(null);
        if (containerId == null) {
            client.sendEvent("gone", "no running container for " + service);
            client.close();
            return;
        }
        final boolean multiplexed = !docker.inspect(containerId).tty();
        final String tail = client.ctx().queryParamAsClass("tail", String.class).getOrDefault("200");
        final String since = client.ctx().queryParam("since");
        final DockerSocket.Stream stream = docker.logs(containerId, true, tail, since);
        follows.serve(client, service, stream, Follows.Wanted.ALWAYS, sink -> {
            backlog(sink, containerId, service, tail, multiplexed);
            LogFrames.read(stream.body(), multiplexed, line -> sink.send("line", line));
        });
    }

    /** {@link AgentWire#LOG_CAPACITY}: lines the console can offer, Docker's and then the archive's. */
    public void capacity(final Context ctx) {
        final int max = Math.min(
                CAPACITY_MAX, ctx.queryParamAsClass("max", Integer.class).getOrDefault(CAPACITY_MAX));
        ctx.json(new AgentWire.LogCapacity(capacity(ctx.pathParam("service"), max)));
    }

    int capacity(final String service, final int max) {
        final String containerId = docker.running(project, service).orElse(null);
        if (containerId == null) {
            return 0;
        }
        final boolean multiplexed = !docker.inspect(containerId).tty();
        final List<String> lines = docker.recentLines(containerId, max, multiplexed);
        if (lines.size() >= max) {
            return max;
        }
        return lines.size()
                + archive.before(service, oldest(lines, clock.instant()), max - lines.size())
                        .lineCount();
    }

    /**
     * What the console shows below the live lines when Docker alone cannot fill the window.
     *
     * Earlier runs from the volume, oldest first, and an {@code end} event first when nothing older is left.
     */
    private void backlog(
            final Follows.Sink sink,
            final String containerId,
            final String service,
            final String tail,
            final boolean multiplexed) {
        final int wanted;
        try {
            wanted = Integer.parseInt(tail);
        } catch (final NumberFormatException all) {
            return;
        }
        final List<String> dockerLines = docker.recentLines(containerId, wanted, multiplexed);
        if (dockerLines.size() >= wanted) {
            return;
        }
        final LogArchive.Backlog earlier =
                archive.before(service, oldest(dockerLines, clock.instant()), wanted - dockerLines.size());
        if (earlier.exhausted()) {
            sink.send("end", "Nothing older.");
        }
        for (final LogArchive.Run run : earlier.runs()) {
            sink.send("run", run.label());
            for (final String line : run.lines()) {
                sink.send("line", line);
            }
        }
    }

    /** The timestamp Docker put in front of the first line, or now when there is none. */
    static Instant oldest(final List<String> dockerLines, final Instant now) {
        if (!dockerLines.isEmpty()) {
            final String first = dockerLines.getFirst();
            final int space = first.indexOf(' ');
            if (space > 0) {
                try {
                    return Instant.parse(first.substring(0, space));
                } catch (final DateTimeParseException notStamped) {
                    // falls through to now
                }
            }
        }
        return now;
    }

    /** Ends every open follow, before Jetty stops. */
    @Override
    public void close() {
        follows.close();
    }
}
