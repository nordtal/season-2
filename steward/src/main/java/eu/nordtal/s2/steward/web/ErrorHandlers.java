package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.messages.Refused;
import eu.nordtal.s2.steward.docker.DockerException;
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

        // The interface recovers from this one: it runs the ceremony and retries the request.
        cfg.routes.exception(
                SecondFactor.SecondFactorRequired.class,
                (required, ctx) -> ctx.status(403)
                        .json(Map.of(
                                "error", required.getMessage(), "code", "SECOND_FACTOR_REQUIRED", "retryable", true)));

        // A refused write is an answer: 409 with the reason to branch on and the sentence to show.
        cfg.routes.exception(
                Refused.class,
                (refused, ctx) -> ctx.status(409)
                        .json(Map.of(
                                "error",
                                DatabaseText.english(refused.refusal().message()),
                                "code",
                                refused.reason().name())));

        // The daemon did not answer or refused: the interface says which, rather than a bare 500.
        cfg.routes.exception(DockerException.class, (failure, ctx) -> {
            log.warn("the Docker daemon did not answer: {}", failure.getMessage());
            ctx.status(502)
                    .json(Map.of(
                            "error",
                            failure.getMessage(),
                            "where",
                            "docker",
                            "detail",
                            failure.body() == null ? "" : failure.body()));
        });

        cfg.routes.exception(InternalClient.Failure.class, (failure, ctx) -> {
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
