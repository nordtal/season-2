package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The plain reads and writes this service forwards to steward-worker rather than answering itself. */
final class WorkerProxy {

    private static final Logger log = LoggerFactory.getLogger(WorkerProxy.class);
    private static final Gson GSON = new Gson();

    /** The shape a backup name must have before it becomes a URL segment and a header value. */
    private static final Pattern FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final InternalClient worker;
    private final Function<Context, DiscordAuth.Account> accounts;

    WorkerProxy(final InternalClient worker, final Function<Context, DiscordAuth.Account> accounts) {
        this.worker = worker;
        this.accounts = accounts;
    }

    void passThrough(final Context ctx, final String path) {
        ctx.contentType("application/json").result(worker.get(path));
    }

    void plugins(final Context ctx) {
        passThrough(ctx, "/api/services/" + ctx.pathParam("name") + "/plugins");
    }

    void services(final Context ctx) {
        passThrough(ctx, "/api/services");
    }

    void service(final Context ctx) {
        passThrough(ctx, "/api/services/" + ctx.pathParam("name"));
    }

    void console(final Context ctx) {
        final String answer = worker.post("/api/services/" + ctx.pathParam("name") + "/console", ctx.body());
        ctx.status(202).contentType("application/json").result(answer);
    }

    /** The unified "latest actions" feed, with its query string forwarded. */
    void actions(final Context ctx) {
        passThrough(ctx, "/api/actions" + StewardUi.forwardedQuery(ctx.queryString()));
    }

    void host(final Context ctx) {
        passThrough(ctx, "/api/host");
    }

    void schedule(final Context ctx) {
        passThrough(ctx, "/api/schedule");
    }

    void backups(final Context ctx) {
        passThrough(ctx, "/api/backups");
    }

    void configRoot(final Context ctx) {
        forwardConfig(ctx, "/api/config", null);
    }

    void configFile(final Context ctx) {
        forwardConfig(ctx, configPath(ctx), null);
    }

    void saveConfigFile(final Context ctx) {
        forwardConfig(ctx, configPath(ctx), ctx.body());
    }

    /** The raw editor's save: a body of text and a revision. */
    void saveConfigRaw(final Context ctx) {
        forwardConfig(ctx, workerPath("/api/config-raw", ctx, "file"), ctx.body());
    }

    void messagesRoot(final Context ctx) {
        forwardConfig(ctx, "/api/messages", null);
    }

    void messageBundle(final Context ctx) {
        forwardConfig(ctx, workerPath("/api/messages", ctx, "bundle"), null);
    }

    void saveMessageBundle(final Context ctx) {
        forwardConfig(ctx, workerPath("/api/messages", ctx, "bundle"), ctx.body());
    }

    void pluginSearch(final Context ctx) {
        passThrough(
                ctx,
                "/api/services/" + ctx.pathParam("name") + "/plugins/search"
                        + StewardUi.forwardedQuery(ctx.queryString()));
    }

    /** Takes the name on the row from the session, never from the browser. */
    void installPlugin(final Context ctx) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Map<String, Object> body =
                new java.util.LinkedHashMap<>(GSON.<Map<String, Object>>fromJson(ctx.body(), Map.class));
        body.put("by", who.name() + " (" + who.id() + ")");
        forwardWorker(ctx, "/api/services/" + ctx.pathParam("name") + "/plugins", GSON.toJson(body), 201);
    }

    void removePlugin(final Context ctx) {
        forwardWorker(
                ctx, "/api/services/" + ctx.pathParam("name") + "/plugins/" + ctx.pathParam("artifact"), null, 200);
    }

    void health(final Context ctx) {
        ctx.json(Map.of("status", "ok", "worker", worker.isReachable()));
    }

    /** Passes the worker's answer through, status and body, so its real status and {@code error} field survive. */
    void forwardConfig(final Context ctx, final String path, final @Nullable String body) {
        try {
            final String answer = body == null ? worker.get(path) : worker.put(path, body);
            ctx.contentType("application/json").result(answer);
        } catch (final InternalClient.Failure failure) {
            if (failure.body() == null || failure.body().isBlank()) {
                throw failure;
            }
            log.info("steward-worker refused {} with {}", path, failure.status());
            ctx.status(failure.status()).contentType("application/json").result(failure.body());
        }
    }

    /** The worker's path for {@code <file>}, re-encoded segment by segment. */
    static String configPath(final Context ctx) {
        return workerPath("/api/config", ctx, "file");
    }

    /** {@code prefix} plus one path parameter, re-encoded segment by segment. */
    static String workerPath(final String prefix, final Context ctx, final String param) {
        final StringBuilder path = new StringBuilder(prefix);
        for (final String segment : ctx.pathParam(param).split("/", -1)) {
            path.append('/')
                    .append(java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
                            .replace("+", "%20"));
        }
        return path.toString();
    }

    /**
     * A write forwarded like {@link #forwardConfig}; a null {@code body} is a DELETE, {@code ok} the success status.
     */
    void forwardWorker(final Context ctx, final String path, final @Nullable String body, final int ok) {
        try {
            final String answer = body == null ? worker.delete(path) : worker.post(path, body);
            ctx.status(ok).contentType("application/json").result(answer);
        } catch (final InternalClient.Failure failure) {
            if (failure.body() == null || failure.body().isBlank()) {
                throw failure;
            }
            log.info("steward-worker refused {} with {}", path, failure.status());
            ctx.status(failure.status()).contentType("application/json").result(failure.body());
        }
    }

    /** Streams one archive straight through, after holding {@code name} to the shape of a filename. */
    void downloadBackup(final Context ctx, final String name) {
        if (!FILENAME.matcher(name).matches()) {
            throw new BadRequestResponse("not the name of a backup: " + name);
        }
        ctx.contentType("application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .result(worker.stream("/api/backups/" + name + "/download"));
    }
}
