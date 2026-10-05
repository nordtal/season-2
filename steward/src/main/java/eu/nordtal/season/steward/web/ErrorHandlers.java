package eu.nordtal.season.steward.web;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Refused;
import eu.nordtal.season.steward.texts.RequestRefused;
import io.javalin.config.JavalinConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The exceptions this service turns into a response shape of its own, rather than a bare 500. */
public final class ErrorHandlers {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlers.class);

    private ErrorHandlers() {}

    /**
     * Installs the handlers.
     *
     * @param texts Steward's bundles with the admins' overrides, which a {@link RequestRefused} is rendered from
     * @param database the database bundle with the admins' overrides, which a {@link Refused} is worded in
     */
    public static void install(final JavalinConfig cfg, final Messages texts, final Messages database) {
        Objects.requireNonNull(texts, "texts");
        Objects.requireNonNull(database, "database");
        // The page recovers from SECOND_FACTOR_REQUIRED: it runs the ceremony and retries the request.
        cfg.routes.exception(RequestRefused.class, (refused, ctx) -> {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", texts.format(Locales.DEFAULT, refused.why()));
            if (refused.code() != null) {
                body.put("code", refused.code());
            }
            if (refused.retryable()) {
                body.put("retryable", true);
            }
            ctx.status(refused.status()).json(body);
        });

        // A refused write is an answer: 409 with the reason to branch on and the sentence to show.
        cfg.routes.exception(
                Refused.class,
                (refused, ctx) -> ctx.status(409)
                        .json(Map.of(
                                "error",
                                database.format(
                                        Locales.DEFAULT, refused.refusal().message()),
                                "code",
                                refused.reason().name())));

        // An internal service refused or did not answer; the agent's own refusal says whether Docker was the cause.
        cfg.routes.exception(InternalClient.Failure.class, (failure, ctx) -> {
            log.warn("{} did not answer: {}", failure.where(), failure.getMessage());
            final AgentWire.Refusal refusal = AgentClient.refusal(failure)
                    .orElseGet(() -> new AgentWire.Refusal(String.valueOf(failure.getMessage()), failure.where()));
            ctx.status(failure.status() == 0 ? 502 : failure.status())
                    .json(Map.of(
                            "error",
                            refusal.error(),
                            "where",
                            refusal.where(),
                            "detail",
                            failure.body() == null ? "" : failure.body()));
        });
    }
}
