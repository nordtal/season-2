package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.steward.journal.Journaling;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Forwards the plugin list, the search and the install of one server to steward-agent with the asker. */
public final class PluginsForward {

    private final AgentClient agent;
    private final UpdateDirectory updates;

    public PluginsForward(final AgentClient agent, final UpdateDirectory updates) {
        this.agent = agent;
        this.updates = updates;
    }

    void list(final Context ctx) {
        ctx.json(agent.plugins(service(ctx)));
    }

    void search(final Context ctx) {
        ctx.json(agent.searchPlugins(service(ctx), Objects.requireNonNullElse(ctx.queryParam("q"), "")));
    }

    /** {@code POST /api/services/{name}/plugins} adds a plugin row. */
    AgentWire.PluginAdded add(final Context ctx, final Actor by) {
        final AgentWire.PluginAsk ask = ctx.bodyAsClass(AgentWire.PluginAsk.class);
        if (ask == null) {
            throw new BadRequestResponse("the body is the Modrinth project to install");
        }
        final AgentWire.PluginAdded added = agent.addPlugin(service(ctx), new AgentWire.AddPlugin(ask, by));
        ctx.status(201).json(added);
        return added;
    }

    /** The answer to {@code DELETE /api/services/{name}/plugins/{artifact}}. */
    public record RemovalAsked(long id, UpdateKind kind, String artifact) {}

    /** {@code DELETE /api/services/{name}/plugins/{artifact}} writes a REMOVE_PLUGIN run request. */
    void remove(final Context ctx, final Actor actor) {
        final String artifact = ctx.pathParam("artifact");
        final UpdateRequest written;
        try {
            written = updates.submit(
                    new StewardRequest.RemovePlugin(List.of(service(ctx)), artifact), actor, Duration.ZERO);
        } catch (final IllegalArgumentException malformed) {
            throw new BadRequestResponse(malformed.getMessage());
        }
        Journaling.line(ctx, new JournalDetail.PluginRemoved(written.id(), service(ctx), artifact));
        ctx.status(202).json(new RemovalAsked(written.id(), written.kind(), artifact));
    }

    private static String service(final Context ctx) {
        final String service = ctx.pathParam("name");
        if (!service.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new BadRequestResponse("\"" + service + "\" is not a service name");
        }
        return service;
    }
}
