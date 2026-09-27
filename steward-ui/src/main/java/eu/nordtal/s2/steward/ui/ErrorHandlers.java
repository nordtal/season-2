package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.config.JavalinConfig;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The exceptions this service turns into a response shape of its own, rather than a bare 500. */
final class ErrorHandlers {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlers.class);

    private ErrorHandlers() {}

    static void install(final JavalinConfig cfg) {
        cfg.routes.exception(
                SecondFactor.SecondFactorMissing.class,
                (missing, ctx) ->
                        ctx.status(403).json(Map.of("error", missing.getMessage(), "code", "SECOND_FACTOR_MISSING")));

        // The refusal the interface recovers from: it runs the ceremony and retries the request.
        cfg.routes.exception(
                SecondFactor.SecondFactorRequired.class,
                (required, ctx) -> ctx.status(403)
                        .json(Map.of(
                                "error", required.getMessage(), "code", "SECOND_FACTOR_REQUIRED", "retryable", true)));

        cfg.routes.exception(InternalClient.Failure.class, (failure, ctx) -> {
            // Names which half is down; an empty table would not.
            log.warn("{} did not answer: {}", failure.where(), failure.getMessage());
            ctx.status(failure.status() == 0 ? 502 : failure.status())
                    .json(Map.of(
                            "error",
                            failure.getMessage(),
                            "where",
                            failure.where(),
                            "detail",
                            failure.body() == null ? "" : failure.body()));
        });
    }
}
