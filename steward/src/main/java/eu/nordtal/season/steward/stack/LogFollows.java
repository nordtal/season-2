package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.LogFollow;
import eu.nordtal.season.internalapi.sse.Follows;
import io.javalin.http.sse.SseClient;
import java.util.function.BooleanSupplier;

/**
 * The live console for the browser: steward-agent's log follow, passed on event by event while the session holds.
 *
 * The agent reads Docker and the rotated logs; this only relays, and ends every follow before Jetty stops.
 */
public final class LogFollows implements AutoCloseable {

    private static final String SIGNED_OUT = "this session ended - sign in again to keep watching";

    private final AgentClient agent;
    private final Follows follows;

    LogFollows(final AgentClient agent, final Scheduler scheduler) {
        this.agent = agent;
        this.follows = new Follows("Steward", scheduler);
    }

    /**
     * One service's log, the backlog first, until the browser leaves, the session ends or the agent stops.
     *
     * @param signedIn asked at most once a second while lines arrive; a {@code false} ends the follow with
     *     {@code gone}
     */
    void serve(final SseClient client, final String name, final BooleanSupplier signedIn) {
        final String tail = client.ctx().queryParamAsClass("tail", String.class).getOrDefault("200");
        final String since = client.ctx().queryParam("since");
        final LogFollow follow;
        try {
            follow = agent.logs(name, tail, since);
        } catch (final InternalClient.Failure unreachable) {
            // A gone event, not an error: the browser would only reconnect into the same answer.
            client.sendEvent("gone", AgentClient.sentence(unreachable));
            client.close();
            return;
        }
        follows.serve(
                client,
                name,
                follow,
                new Follows.Wanted(signedIn, SIGNED_OUT),
                sink -> follow.read(event -> sink.send(event.event(), event.data())));
    }

    @Override
    public void close() {
        follows.close();
    }
}
