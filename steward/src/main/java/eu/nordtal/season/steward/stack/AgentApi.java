package eu.nordtal.season.steward.stack;

import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.jspecify.annotations.Nullable;

/** Answers whether steward-agent can be asked. */
public final class AgentApi {

    private final AgentClient agent;
    private final boolean configured;

    /** @param configured whether a secret is set */
    public AgentApi(final AgentClient agent, final boolean configured) {
        this.agent = agent;
        this.configured = configured;
    }

    /** The state of the agent. {@code reason} is set when it cannot be asked, and {@code reachable} when it can. */
    public record AgentState(
            boolean available,
            @Nullable MessageRef reason,
            @Nullable Boolean reachable) {}

    public void state(final Context ctx) {
        if (!configured) {
            ctx.json(new AgentState(false, StewardTexts.TEXTS.steward().answer().agentUnconfigured(), null));
            return;
        }
        ctx.json(new AgentState(true, null, agent.isReachable()));
    }

    public void register(final JavalinConfig cfg) {
        cfg.routes.get("/api/agent", this::state, Gate.KEY_HELD);
    }
}
