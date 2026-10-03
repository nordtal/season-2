package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.Refused;
import eu.nordtal.s2.steward.texts.RequestRefused;
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
     */
    public static void install(final JavalinConfig cfg, final Messages texts) {
        Objects.requireNonNull(texts, "texts");
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
                                DatabaseText.english(refused.refusal().message()),
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
