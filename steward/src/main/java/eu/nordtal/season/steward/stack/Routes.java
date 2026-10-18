package eu.nordtal.season.steward.stack;

import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import java.util.Optional;

/**
 * Registers every route of {@link StackApi} with its {@link Gate}.
 * Reads and easily undone saves use {@link Gate#KEY_HELD}. Routes that replace, remove or run code use
 * {@link Gate#KEY_FRESH}.
 */
public final class Routes {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private Routes() {}

    static void register(final StackApi api, final JavalinConfig config, final Caller caller) {
        serviceRoutes(api, config, caller);
        consoleRoute(api, config, caller);
        messageRoutes(api, config, caller);
        settingRoutes(api, config, caller);
        hostRoutes(api, config, caller);
        pluginRoutes(api, config, caller);
        availableRoute(api, config);
    }

    /** Registers the service table, one service's row and its live log. */
    private static void serviceRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.get("/api/services", ctx -> ctx.json(api.serviceTable()), Gate.KEY_HELD);
        config.routes.get("/api/topology", ctx -> ctx.json(api.network()), Gate.KEY_HELD);

        config.routes.get(
                "/api/services/{name}",
                ctx -> ctx.json(api.service(ctx.pathParam("name"))
                        .orElseThrow(() -> new RequestRefused(404, ANSWER.noService(ctx.pathParam("name"))))),
                Gate.KEY_HELD);

        config.routes.sse(
                "/api/services/{name}/logs",
                client -> {
                    if (!caller.stillSignedIn(client.ctx())) {
                        client.close();
                        return;
                    }
                    api.logFollows.serve(
                            client, client.ctx().pathParam("name"), () -> caller.stillSignedIn(client.ctx()));
                },
                Gate.KEY_HELD);
    }

    /** Registers the route that types one line into a server's console. The answer appears in the service log. */
    private static void consoleRoute(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.post(
                "/api/services/{name}/console",
                ctx -> {
                    final StackApi.ConsoleLine body = ctx.bodyAsClass(StackApi.ConsoleLine.class);
                    if (body == null || body.command == null || body.command.isBlank()) {
                        throw new BadRequestResponse("command is the line to type");
                    }
                    api.console(ctx.pathParam("name"), body.command.strip(), caller.actor(ctx));
                    Journaling.line(ctx, new JournalDetail.ConsoleTyped(ctx.pathParam("name"), body.command.strip()));
                    ctx.status(202)
                            .json(new ConsoleSent(body.command.strip(), "the answer appears in this service's log"));
                },
                Gate.KEY_FRESH,
                Journaled.as(JournalAction.CONSOLE));
    }

    /** Registers the routes for the setting groups, their descriptors and their save. */
    private static void settingRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.get("/api/descriptors", ctx -> ctx.json(api.agent.descriptors()), Gate.KEY_HELD);
        config.routes.get("/api/setting-groups", ctx -> api.settings().list(ctx), Gate.KEY_HELD);
        config.routes.get(
                "/api/setting-groups/{service}/{name}", ctx -> api.settings().one(ctx), Gate.KEY_HELD);
        config.routes.put(
                "/api/setting-groups/{service}/{name}",
                ctx -> {
                    api.settings().save(ctx, caller.actor(ctx));
                    Journaling.line(
                            ctx, new JournalDetail.SettingsSaved(ctx.pathParam("service"), ctx.pathParam("name")));
                },
                Gate.KEY_HELD,
                Journaled.as(JournalAction.SAVE_SETTINGS));
    }

    /** Registers the routes for message bundles and Discord documents. */
    private static void messageRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.get("/api/messages", api.messages::list, Gate.KEY_HELD);
        config.routes.get("/api/message-fallbacks", api.messages::fallbacks, Gate.KEY_HELD);
        config.routes.get("/api/message-check", api.messages::check, Gate.KEY_HELD);
        config.routes.get("/api/message-syntax", api.messages::syntax, Gate.KEY_HELD);
        config.routes.put(
                "/api/messages",
                ctx -> {
                    for (final String bundle : api.messages.save(ctx, caller.actor(ctx))) {
                        Journaling.line(ctx, new JournalDetail.MessagesSaved(bundle));
                    }
                },
                Gate.KEY_HELD,
                Journaled.as(JournalAction.SAVE_MESSAGES));
        config.routes.get("/api/documents", api.documents::list, Gate.KEY_HELD);
        config.routes.get("/api/document-images/{id}", api.documents::image, Gate.KEY_HELD);
    }

    /** Registers the routes for the host numbers, the schedule, the backups and the action feed. */
    private static void hostRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.get("/api/host", ctx -> ctx.json(api.hostNumbers()), Gate.KEY_HELD);

        config.routes.get("/api/schedule", ctx -> ctx.json(api.schedule()), Gate.KEY_HELD);

        config.routes.get("/api/backups", ctx -> ctx.json(api.archives()), Gate.KEY_HELD);

        config.routes.get(
                "/api/backups/{name}/download",
                ctx -> {
                    final String name = ctx.pathParam("name");
                    final Optional<AgentWire.Archive> listed = api.download(ctx, name);
                    Journaling.line(
                            ctx,
                            new JournalDetail.BackupDownloaded(
                                    name, listed.map(AgentWire.Archive::bytes).orElse(null)));
                },
                Gate.KEY_HELD,
                Journaled.as(JournalAction.DOWNLOAD_BACKUP));

        config.routes.post(
                "/api/backups/{name}/restore",
                ctx -> api.restore(ctx, caller.actor(ctx)),
                Gate.KEY_FRESH,
                Journaled.as(JournalAction.REQUEST_RUN));

        config.routes.get("/api/actions", api.actions::list, Gate.KEY_HELD);
    }

    /** Registers the routes for the plugins of one Minecraft server and the Modrinth search. */
    private static void pluginRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.get("/api/services/{name}/plugins", ctx -> api.plugins().list(ctx), Gate.KEY_HELD);
        config.routes.get(
                "/api/services/{name}/plugins/search", ctx -> api.plugins().search(ctx), Gate.KEY_HELD);
        config.routes.post(
                "/api/services/{name}/plugins",
                ctx -> {
                    final AgentWire.PluginAdded added = api.plugins().add(ctx, caller.actor(ctx));
                    Journaling.line(ctx, new JournalDetail.PluginAdded(ctx.pathParam("name"), added.artifact()));
                },
                Gate.KEY_FRESH,
                Journaled.as(JournalAction.ADD_PLUGIN));
        config.routes.delete(
                "/api/services/{name}/plugins/{artifact}",
                ctx -> api.plugins().remove(ctx, caller.actor(ctx)),
                Gate.KEY_FRESH,
                Journaled.as(JournalAction.REMOVE_PLUGIN));
    }

    /** Registers the route that answers what a run would do. It writes nothing. */
    private static void availableRoute(final StackApi api, final JavalinConfig config) {
        // Javalin matches in registration order, so this precedes /api/updates/{id}, which the web registers.
        config.routes.get(
                "/api/updates/available",
                ctx -> {
                    final Refreshed<AgentWire.Resolve> available = api.available;
                    if (available == null) {
                        throw new RequestRefused(
                                503, StewardTexts.TEXTS.steward().answer().noSources());
                    }
                    if (ctx.queryParam("refresh") != null) {
                        available.invalidate();
                    }
                    ctx.json(available.get());
                },
                Gate.KEY_HELD);
    }

    /**
     * The answer to a console line.
     *
     * @param where the place where the answer shows
     */
    public record ConsoleSent(String sent, String where) {}
}
