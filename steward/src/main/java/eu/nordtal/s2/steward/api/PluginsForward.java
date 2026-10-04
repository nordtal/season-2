package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.inbox.StewardRequest;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The plugins on one server: the list, the search and the install are steward-agent's, passed on with who asked.
 *
 * Removing one stops its server, so it is a run like every other stop.
 */
public final class PluginsForward {

    private final AgentClient agent;
    private final UpdateDirectory updates;

    public PluginsForward(final AgentClient agent, final UpdateDirectory updates) {
        this.agent = agent;
        this.updates = updates;
    }

    /** {@code GET /api/services/{name}/plugins} */
    void list(final Context ctx) {
        ctx.json(agent.plugins(service(ctx)));
    }

    /** {@code GET /api/services/{name}/plugins/search?q=} */
    void search(final Context ctx) {
        ctx.json(agent.searchPlugins(service(ctx), Objects.requireNonNullElse(ctx.queryParam("q"), "")));
    }

    /** {@code POST /api/services/{name}/plugins}: a row the next update run fulfils, answered and returned. */
    AgentWire.PluginAdded add(final Context ctx, final Actor by) {
        final AgentWire.PluginAsk ask = ctx.bodyAsClass(AgentWire.PluginAsk.class);
        if (ask == null) {
            throw new BadRequestResponse("the body is the Modrinth project to install");
        }
        final AgentWire.PluginAdded added = agent.addPlugin(service(ctx), new AgentWire.AddPlugin(ask, by));
        ctx.status(201).json(added);
        return added;
    }

    /** {@code DELETE /api/services/{name}/plugins/{artifact}}: the run that removes it, by its id. */
    public record RemovalAsked(long id, UpdateKind kind, String artifact) {}

    /** {@code DELETE /api/services/{name}/plugins/{artifact}}: a REMOVE_PLUGIN run, answered with its row. */
    void remove(final Context ctx, final Actor actor) {
        final String artifact = ctx.pathParam("artifact");
        final UpdateRequest written;
        try {
            written = updates.submit(
                    new StewardRequest.RemovePlugin(List.of(service(ctx)), artifact), actor, Duration.ZERO);
        } catch (final IllegalArgumentException malformed) {
            throw new BadRequestResponse(malformed.getMessage());
        }
        ctx.status(202).json(new RemovalAsked(written.id(), written.kind(), artifact));
    }

    /** The service out of the path, checked since it goes into the agent's URL. */
    private static String service(final Context ctx) {
        final String service = ctx.pathParam("name");
        if (!service.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new BadRequestResponse("\"" + service + "\" is not a service name");
        }
        return service;
    }
}
