package eu.nordtal.season.steward.web;

import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import org.jspecify.annotations.Nullable;

/**
 * Whether steward-agent can be asked at all, so a page can draw what needs it before anybody clicks.
 *
 * Making a container again is a RECREATE run, asked for like every other run.
 */
public final class AgentApi {

    private final AgentClient agent;
    private final boolean configured;

    /** {@code configured} is whether a secret was given at all; a stack not set up yet is not a fault. */
    public AgentApi(final AgentClient agent, final boolean configured) {
        this.agent = agent;
        this.configured = configured;
    }

    /** Whether steward can ask the agent; {@code reason} only when it cannot, {@code reachable} only when it can. */
    public record AgentState(
            boolean available,
            @Nullable MessageRef reason,
            @Nullable Boolean reachable) {}

    /** Whether the button may be drawn, so a page can decide before anybody clicks. */
    public void state(final Context ctx) {
        if (!configured) {
            ctx.json(new AgentState(false, StewardTexts.TEXTS.steward().answer().agentUnconfigured(), null));
            return;
        }
        ctx.json(new AgentState(true, null, agent.isReachable()));
    }
}
