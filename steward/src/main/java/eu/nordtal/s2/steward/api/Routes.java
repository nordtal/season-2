package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.steward.auth.Gate;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import java.util.Map;

/**
 * Every route {@link StackApi} serves, each with the {@link Gate} it needs.
 *
 * A read is {@link Gate#KEY_HELD}, anything that changes the stack {@link Gate#KEY_FRESH}.
 */
final class Routes {

    private Routes() {}

    /** Wires every route onto {@code config}. */
    static void register(final StackApi api, final JavalinConfig config, final Caller caller) {
        serviceRoutes(api, config, caller);
        consoleRoute(api, config, caller);
        messageRoutes(api, config, caller);
        settingRoutes(api, config, caller);
        hostRoutes(api, config, caller);
        pluginRoutes(api, config, caller);
        availableRoute(api, config);
    }

    /** The service table and one service's row, plus its live log. */
    private static void serviceRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        // One round trip per table: state, health, image, uptime, RAM and CPU together.
        config.routes.get("/api/services", ctx -> ctx.json(api.serviceTable()), Gate.KEY_HELD);
        config.routes.get("/api/topology", ctx -> ctx.json(api.network()), Gate.KEY_HELD);

        config.routes.get(
                "/api/services/{name}",
                ctx -> ctx.json(api.service(ctx.pathParam("name"))
                        .orElseThrow(() ->
                                new io.javalin.http.NotFoundResponse("no such service: " + ctx.pathParam("name")))),
                Gate.KEY_HELD);

        // SSE: one direction, reconnects itself, no reverse-proxy rule.
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

    /**
     * One line into one server's console.
     *
     * The answer is not in the response: the server prints it on its own console, where every admin sees it.
     */
    private static void consoleRoute(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.post(
                "/api/services/{name}/console",
                ctx -> {
                    final StackApi.ConsoleLine body = ctx.bodyAsClass(StackApi.ConsoleLine.class);
                    if (body == null || body.command == null || body.command.isBlank()) {
                        throw new BadRequestResponse("command is the line to type");
                    }
                    // The agent refuses a service without a console with a 400 that names the ones with one.
                    api.console(ctx.pathParam("name"), body.command.strip(), caller.actor(ctx));
                    api.journal(AuditLine.of(
                            "CONSOLE",
                            caller.actor(ctx),
                            Map.of("service", ctx.pathParam("name"), "command", body.command.strip())));
                    ctx.status(202)
                            .json(new ConsoleSent(body.command.strip(), "the answer appears in this service's log"));
                },
                Gate.KEY_FRESH);
    }

    /** Every process's settings: the groups it published, how each plugin is shown, and a save that writes rows. */
    private static void settingRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        // Each jar's own word on its name, logo and custom editors, which the sidebar groups the settings by.
        config.routes.get("/api/descriptors", ctx -> ctx.json(api.agent.descriptors()), Gate.KEY_HELD);
        config.routes.get("/api/setting-groups", ctx -> api.settings().list(ctx), Gate.KEY_HELD);
        config.routes.get(
                "/api/setting-groups/{service}/{name}", ctx -> api.settings().one(ctx), Gate.KEY_HELD);
        config.routes.put(
                "/api/setting-groups/{service}/{name}",
                ctx -> {
                    api.settings().save(ctx, caller.actor(ctx));
                    api.journal(AuditLine.of(
                            "SAVE_SETTINGS",
                            caller.actor(ctx),
                            Map.of("service", ctx.pathParam("service"), "group", ctx.pathParam("name"))));
                },
                Gate.KEY_FRESH);
    }

    /** The message bundles, on their own routes. */
    private static void messageRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.get("/api/messages", api.messages::list, Gate.KEY_HELD);
        config.routes.get("/api/message-fallbacks", api.messages::fallbacks, Gate.KEY_HELD);
        config.routes.get("/api/messages/<bundle>", api.messages::one, Gate.KEY_HELD);
        config.routes.put(
                "/api/messages/<bundle>",
                ctx -> {
                    api.messages.save(ctx, caller.actor(ctx));
                    api.journal(AuditLine.of(
                            "SAVE_MESSAGES", caller.actor(ctx), Map.of("bundle", ctx.pathParam("bundle"))));
                },
                Gate.KEY_FRESH);
    }

    /** The host numbers, the nightly schedule, the backup list, its download and its restore, and the feed. */
    private static void hostRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.get("/api/host", ctx -> ctx.json(api.hostNumbers()), Gate.KEY_HELD);

        // "Tonight" means a moment on this host, not in the browser's zone.
        config.routes.get("/api/schedule", ctx -> ctx.json(api.schedule()), Gate.KEY_HELD);

        // What is actually on the disk, not what a run reported.
        config.routes.get("/api/backups", ctx -> ctx.json(api.archives()), Gate.KEY_HELD);

        // Streamed, not buffered, since these are hundreds of megabytes; reading a backup is a read.
        config.routes.get(
                "/api/backups/{name}/download", ctx -> api.download(ctx, ctx.pathParam("name")), Gate.KEY_HELD);

        // A run like every other stop: the body names what it replaces, so a wrong click restores nothing.
        config.routes.post("/api/backups/{name}/restore", ctx -> api.restore(ctx, caller.actor(ctx)), Gate.KEY_FRESH);

        // The newest rows across the run inbox and audit_log, merged.
        config.routes.get("/api/actions", api.actions::list, Gate.KEY_HELD);
    }

    /**
     * The plugins on one Minecraft server, and the Modrinth search beside them.
     *
     * Installing writes a row the next update run fulfils; removing is a run that stops the server first.
     */
    private static void pluginRoutes(final StackApi api, final JavalinConfig config, final Caller caller) {
        config.routes.get("/api/services/{name}/plugins", ctx -> api.plugins().list(ctx), Gate.KEY_HELD);
        config.routes.get(
                "/api/services/{name}/plugins/search", ctx -> api.plugins().search(ctx), Gate.KEY_HELD);
        // Who asked comes from the session, never from the browser.
        config.routes.post(
                "/api/services/{name}/plugins",
                ctx -> {
                    final AgentWire.PluginAdded added = api.plugins().add(ctx, caller.actor(ctx));
                    api.journal(AuditLine.of(
                            "ADD_PLUGIN",
                            caller.actor(ctx),
                            Map.of("service", ctx.pathParam("name"), "artifact", added.artifact())));
                },
                Gate.KEY_FRESH);
        config.routes.delete(
                "/api/services/{name}/plugins/{artifact}",
                ctx -> api.plugins().remove(ctx, caller.actor(ctx)),
                Gate.KEY_FRESH);
    }

    /**
     * What a run would do, without doing it.
     *
     * Reading this route writes nothing: no request row, no container touched, no jar moved.
     */
    private static void availableRoute(final StackApi api, final JavalinConfig config) {
        // Before /api/updates/{id}, which the web registers later: Javalin matches in registration order.
        config.routes.get(
                "/api/updates/available",
                ctx -> {
                    final Refreshed<AgentWire.Resolve> available = api.available;
                    if (available == null) {
                        // 503, not an empty plan, since those are different answers.
                        ctx.status(503)
                                .json(Map.of("error", "Steward has no sources configured, so nothing can be resolved"));
                        return;
                    }
                    // Asked again on purpose right after publishing: a parameter that throws the cache away.
                    if (ctx.queryParam("refresh") != null) {
                        available.invalidate();
                    }
                    ctx.json(available.get());
                },
                Gate.KEY_HELD);
    }

    /**
     * A console line sent to a service.
     *
     * @param where where its answer shows, since the console answers into the service's log and not here
     */
    public record ConsoleSent(String sent, String where) {}
}
