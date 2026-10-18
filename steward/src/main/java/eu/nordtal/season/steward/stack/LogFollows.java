package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.LogFollow;
import eu.nordtal.season.internalapi.sse.Follows;
import io.javalin.http.sse.SseClient;
import java.util.function.BooleanSupplier;

/** Relays the log follow of steward-agent to the browser as server-sent events while the session is valid. */
public final class LogFollows implements AutoCloseable {

    private static final String SIGNED_OUT = "this session ended - sign in again to keep watching";

    private final AgentClient agent;
    private final Follows follows;

    LogFollows(final AgentClient agent, final Scheduler scheduler) {
        this.agent = agent;
        this.follows = new Follows("Steward", scheduler);
    }

    /**
     * Streams the log of one service, the backlog first, until the browser leaves, the session ends or the agent stops.
     *
     * @param signedIn checked at most once a second while lines arrive, and {@code false} ends the follow with
     *     {@code gone}
     */
    void serve(final SseClient client, final String name, final BooleanSupplier signedIn) {
        final String tail = client.ctx().queryParamAsClass("tail", String.class).getOrDefault("200");
        final String since = client.ctx().queryParam("since");
        final LogFollow follow;
        try {
            follow = agent.logs(name, tail, since);
        } catch (final InternalClient.Failure unreachable) {
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
