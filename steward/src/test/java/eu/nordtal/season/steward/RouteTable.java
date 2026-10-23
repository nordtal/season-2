package eu.nordtal.season.steward;

import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import java.util.List;

/** The routes of a running service, not read from its source. */
final class RouteTable {

    private RouteTable() {}

    static List<Endpoint> endpoints(final Javalin app) {
        return app.unsafe.internalRouter.allHttpHandlers().stream()
                .map(parsed -> parsed.endpoint)
                // BEFORE and AFTER are filters with no decision of their own, such as `guard`.
                .filter(endpoint -> !endpoint.method.equals(HandlerType.BEFORE)
                        && !endpoint.method.equals(HandlerType.BEFORE_MATCHED)
                        && !endpoint.method.equals(HandlerType.AFTER)
                        && !endpoint.method.equals(HandlerType.AFTER_MATCHED))
                .toList();
    }
}
