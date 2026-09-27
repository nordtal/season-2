package eu.nordtal.s2.steward.worker.api;

import com.google.gson.Gson;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.json.JavalinGson;
import java.util.Map;

/** Every route {@link WorkerApi} serves, registered on the config Javalin hands to {@code Javalin.create} at start. */
final class Routes {

    private Routes() {}

    /** Wires every route onto {@code config} - the body of {@link WorkerApi#start}, split out for its own length. */
    static void register(final WorkerApi api, final JavalinConfig config) {
        config.jsonMapper(new JavalinGson(new Gson(), true));
        config.startup.showJavalinBanner = false;

        gate(api, config);
        serviceRoutes(api, config);
        consoleRoute(api, config);
        configRoutes(api, config);
        messageRoutes(api, config);
        hostRoutes(api, config);
        pluginRoutes(api, config);
        availableRoute(api, config);
    }

    /** The token check every route but the health check must pass. */
    private static void gate(final WorkerApi api, final JavalinConfig config) {
        config.routes.before("/api/*", ctx -> {
            if (ctx.path().equals("/api/health")) {
                return;
            }
            if (!api.token.equals(ctx.header("X-Steward-Token"))) {
                throw new io.javalin.http.UnauthorizedResponse("bad or missing X-Steward-Token");
            }
        });
    }

    /** The health check, the service table and one service's row, plus its live log. */
    private static void serviceRoutes(final WorkerApi api, final JavalinConfig config) {
        config.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "docker", api.docker.isReachable())));

        // Ten round trips per table would be slower than the table: state, health, image, uptime, RAM, CPU in one.
        config.routes.get("/api/services", ctx -> ctx.json(api.serviceTable()));

        config.routes.get(
                "/api/services/{name}",
                ctx -> ctx.json(api.service(ctx.pathParam("name"))
                        .orElseThrow(() ->
                                new io.javalin.http.NotFoundResponse("no such service: " + ctx.pathParam("name")))));

        // SSE, not a websocket: one direction, reconnects itself, no reverse-proxy rule - see LogFollows for the rest.
        config.routes.sse("/api/services/{name}/logs", client -> {
            final String name = client.ctx().pathParam("name");
            final String containerId =
                    Archives.containerOf(api.docker, api.project, name).orElse(null);
            if (containerId == null) {
                client.sendEvent("gone", "no running container for " + name);
                client.close();
                return;
            }
            api.logFollows.serve(client, containerId, name);
        });
    }

    /**
     * One line into one server's console.
     *
     * The answer is NOT in the response: {@code mc} hands the line to tmux and the server prints its reply on its
     * own console, the log everybody is already watching - which is what makes a second admin's command visible
     * to the first instead of private.
     */
    private static void consoleRoute(final WorkerApi api, final JavalinConfig config) {
        config.routes.post("/api/services/{name}/console", ctx -> {
            final WorkerApi.ConsoleLine body = ctx.bodyAsClass(WorkerApi.ConsoleLine.class);
            if (body == null || body.command == null || body.command.isBlank()) {
                throw new BadRequestResponse("command is the line to type");
            }
            try {
                api.console.send(ctx.pathParam("name"), body.command.strip());
            } catch (IllegalArgumentException e) {
                throw new BadRequestResponse(e.getMessage());
            }
            ctx.status(202)
                    .json(Map.of("sent", body.command.strip(), "where", "the answer appears in this service's log"));
        });
    }

    /**
     * The configuration of every service in the stack, and the raw editor's own save.
     *
     * steward-ui proxies the first three verbatim: it draws the form and holds the security key in front of it,
     * and this side holds the file permissions - neither half can do the other's job. The raw save is its own
     * route rather than a fourth verb on those three, because it carries text and a revision, never a
     * {@code changes} map, and nothing here refuses on what that text says - see {@code ConfigApi#saveRaw}.
     */
    private static void configRoutes(final WorkerApi api, final JavalinConfig config) {
        config.routes.get("/api/config", api.configs::list);
        config.routes.get("/api/config/<file>", api.configs::one);
        config.routes.put("/api/config/<file>", api.configs::save);
        config.routes.put("/api/config-raw/<file>", api.configs::saveRaw);
    }

    /** The message bundles - their own routes and their own card in the interface; see {@code MessagesApi} for why. */
    private static void messageRoutes(final WorkerApi api, final JavalinConfig config) {
        config.routes.get("/api/messages", api.messages::list);
        config.routes.get("/api/messages/<bundle>", api.messages::one);
        config.routes.put("/api/messages/<bundle>", api.messages::save);
    }

    /** The host numbers, the nightly schedule, the backup list and its download, the alert level and the feed. */
    private static void hostRoutes(final WorkerApi api, final JavalinConfig config) {
        config.routes.get("/api/host", ctx -> ctx.json(api.hostNumbers()));

        // "Tonight" means a moment on THIS host, not the browser's zone - it must land before the backup, not on it.
        config.routes.get("/api/schedule", ctx -> ctx.json(api.schedule()));

        // What is actually on the disk, not what a run reported - the report is a list of things somebody meant.
        config.routes.get("/api/backups", ctx -> ctx.json(Archives.list(api.backups)));

        // Streamed, not buffered - hundreds of megabytes; downloadBackup's own javadoc has the two checks made.
        config.routes.get(
                "/api/backups/{name}/download", ctx -> Archives.download(ctx, api.backups, ctx.pathParam("name")));

        // A small derived reading, not the service table again - see AlertLevel for why it is a subset of it.
        config.routes.get("/api/alert-level", ctx -> ctx.json(api.alertLevel()));

        // The newest rows across update_request and audit_log, merged here - see ActionsApi for why one query.
        config.routes.get("/api/actions", api.actions::list);
    }

    /**
     * The plugins on one Minecraft server, and the Modrinth search beside them.
     *
     * The list is read off the disk and the table only says which rows may be deleted - see PluginsApi for why
     * that is the enforcement of "the Nordtal plugins are fixed" rather than a greyed-out button.
     *
     * Installing is a row and not an install: the jar arrives with the next update run, through the ordinary
     * resolve, because Topology.servicesWith merges the table into the fixed list. Removing is the one thing
     * here that touches the disk at once, and it deletes the data folder as well - which is why the list hands
     * its name over first.
     */
    private static void pluginRoutes(final WorkerApi api, final JavalinConfig config) {
        config.routes.get("/api/services/{name}/plugins", ctx -> api.plugins().list(ctx));
        config.routes.get(
                "/api/services/{name}/plugins/search", ctx -> api.plugins().search(ctx));
        config.routes.post("/api/services/{name}/plugins", ctx -> api.plugins().add(ctx));
        config.routes.delete(
                "/api/services/{name}/plugins/{artifact}", ctx -> api.plugins().remove(ctx));
    }

    /**
     * WHAT A RUN WOULD DO, WITHOUT DOING IT.
     *
     * Until this existed the only way to see whether PacketEvents or Paper had moved was to start a run, so the
     * plan page said in as many words that there was no dry run and drew an image comparison instead. Reading
     * this route writes nothing: no row in update_request, no container touched, no jar moved. See
     * {@code WorkerApi#AVAILABLE_TTL} for why that is not a breach of "nothing updates on a schedule" but the
     * other half of it.
     */
    private static void availableRoute(final WorkerApi api, final JavalinConfig config) {
        config.routes.get("/api/updates/available", ctx -> {
            if (api.available == null) {
                // 503, not an empty plan: the field's javadoc has why those are different answers.
                ctx.status(503)
                        .json(Map.of("error", "this worker has no sources configured, so nothing can be resolved"));
                return;
            }
            // Asked again on purpose, right after publishing - a read parameter that throws the cache away.
            if (ctx.queryParam("refresh") != null) {
                api.available.invalidate();
            }
            ctx.json(api.availability(api.available.get()));
        });
    }
}
