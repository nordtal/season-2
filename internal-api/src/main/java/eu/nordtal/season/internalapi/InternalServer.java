package eu.nordtal.season.internalapi;

import eu.nordtal.season.common.health.Readiness;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.time.Scheduler;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.json.JavalinGson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves JSON on Javalin for a service that only steward calls.
 * Every route but {@link #HEALTH} needs the shared secret.
 */
public final class InternalServer {

    private static final Logger log = LoggerFactory.getLogger(InternalServer.class);

    public static final String TOKEN_HEADER = "X-Steward-Token";

    /** The one unguarded route. */
    public static final String HEALTH = "/api/health";

    private final String service;
    private final Function<String, @Nullable String> environment;

    /** Describes {@code service}. Its settings are environment variables, read through {@code environment}. */
    public InternalServer(final String service, final Function<String, @Nullable String> environment) {
        this.service = service;
        this.environment = environment;
    }

    public String variable(final String setting) {
        return "NORDTAL_" + service.toUpperCase(Locale.ROOT).replace('-', '_') + "_" + setting;
    }

    public String setting(final String setting, final String fallback) {
        final String value = environment.apply(variable(setting));
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Starts serving {@code routes} on the port in {@code PORT}, or on {@code defaultPort}.
     * Throws {@link IllegalStateException} without a {@code TOKEN}.
     */
    public Javalin start(final int defaultPort, final Consumer<JavalinConfig> routes) {
        final int port = Integer.parseInt(setting("PORT", String.valueOf(defaultPort)));
        final Javalin server = create(setting("TOKEN", ""), routes).start(port);
        log.info("{} listening on {}", service, port);
        return server;
    }

    /** Starts as {@link #start} does, then keeps the readiness marker fresh. The container's health check reads it. */
    public Javalin serve(
            final int defaultPort, final Clock clock, final Scheduler scheduler, final Consumer<JavalinConfig> routes) {
        final Javalin server = start(defaultPort, routes);
        final var _ = Readiness.onDefaultPath(clock, log::warn).keepBeating(scheduler);
        return server;
    }

    Javalin create(final String token, final Consumer<JavalinConfig> routes) {
        if (token.isBlank()) {
            throw new IllegalStateException(variable("TOKEN") + " is not set. " + service + " will not serve without"
                    + " the secret it shares with steward, or anything on its network could use it. The setup script"
                    + " writes one.");
        }
        final byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        return Javalin.create(config -> {
            config.jsonMapper(new JavalinGson(Json.gson(), true));
            config.startup.showJavalinBanner = false;
            config.routes.before("/api/*", ctx -> {
                if (!ctx.path().equals(HEALTH) && !matches(expected, ctx.header(TOKEN_HEADER))) {
                    throw new UnauthorizedResponse("bad or missing " + TOKEN_HEADER);
                }
            });
            config.routes.get(HEALTH, ctx -> ctx.json(Map.of("status", "ok")));
            routes.accept(config);
        });
    }

    /**
     * Returns the body of the request as {@code type}, or a 400 with the reason.
     * A body that is no JSON or that a record constructor refuses is the caller's mistake.
     */
    public static <T> T body(final Context ctx, final Class<T> type) {
        final T body;
        try {
            body = ctx.bodyAsClass(type);
        } catch (final RuntimeException refused) {
            Throwable cause = refused;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw new BadRequestResponse("not a " + type.getSimpleName() + ": " + cause.getMessage());
        }
        if (body == null) {
            throw new BadRequestResponse("expected a " + type.getSimpleName() + " as the body");
        }
        return body;
    }

    /** Compares in constant time. */
    private static boolean matches(final byte[] expected, final @Nullable String presented) {
        return presented != null && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
