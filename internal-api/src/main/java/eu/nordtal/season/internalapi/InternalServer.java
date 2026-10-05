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
 * How a service only steward may reach answers it: JSON on Javalin, every route but {@link #HEALTH} behind a secret.
 *
 * steward-agent and steward-bunq each run one; steward is their one caller, through {@link InternalClient}.
 */
public final class InternalServer {

    private static final Logger log = LoggerFactory.getLogger(InternalServer.class);

    /** The header the shared secret travels in. */
    public static final String TOKEN_HEADER = "X-Steward-Token";

    /** The one unguarded route, for {@link InternalClient#isReachable()}; the container's health is the marker. */
    public static final String HEALTH = "/api/health";

    private final String service;
    private final Function<String, @Nullable String> environment;

    /**
     * Describes {@code service}, whose settings are its environment variables.
     *
     * @param environment reads one variable, {@code System::getenv} outside a test
     */
    public InternalServer(final String service, final Function<String, @Nullable String> environment) {
        this.service = service;
        this.environment = environment;
    }

    /** Returns the variable a setting of this service is read from: {@code NORDTAL_STEWARD_BUNQ_PORT} for PORT. */
    public String variable(final String setting) {
        return "NORDTAL_" + service.toUpperCase(Locale.ROOT).replace('-', '_') + "_" + setting;
    }

    /** Returns a setting from the environment, or {@code fallback} while it is unset or blank. */
    public String setting(final String setting, final String fallback) {
        final String value = environment.apply(variable(setting));
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Starts serving {@code routes} on the port in {@code PORT}, or {@code defaultPort}.
     *
     * @throws IllegalStateException without a {@code TOKEN}, since anyone on the network could then use the service
     */
    public Javalin start(final int defaultPort, final Consumer<JavalinConfig> routes) {
        final int port = Integer.parseInt(setting("PORT", String.valueOf(defaultPort)));
        final Javalin server = create(setting("TOKEN", ""), routes).start(port);
        log.info("{} listening on {}", service, port);
        return server;
    }

    /**
     * Starts as {@link #start} does, then keeps the readiness marker fresh that the container's healthcheck reads.
     *
     * @param clock the process's one clock, which stamps the marker
     * @param scheduler the process's one scheduler, which beats it
     */
    public Javalin serve(
            final int defaultPort, final Clock clock, final Scheduler scheduler, final Consumer<JavalinConfig> routes) {
        final Javalin server = start(defaultPort, routes);
        final var _ = Readiness.onDefaultPath(clock, log::warn).keepBeating(scheduler);
        return server;
    }

    /** Builds the server with {@code token}, without starting it, so a test can choose its own port. */
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
     * The request's body as {@code type}, or a 400 that says why it is not one, never a 500.
     *
     * A wire record that refuses a value in its constructor is the caller's mistake, and so is a body that is no JSON.
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

    /** Compares in constant time, so the answer's timing says nothing about how much of a guess was right. */
    private static boolean matches(final byte[] expected, final @Nullable String presented) {
        return presented != null && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
