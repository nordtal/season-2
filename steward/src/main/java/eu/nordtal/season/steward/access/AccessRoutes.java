package eu.nordtal.season.steward.access;

import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Unjournaled;
import io.javalin.config.JavalinConfig;

/** Registers the routes of the people, their payments and journal, their access and their pack exemption. */
public final class AccessRoutes {

    private AccessRoutes() {}

    public static void register(
            final JavalinConfig cfg,
            final RosterRoutes roster,
            final AccessApi access,
            final PackExemptionApi packExemptions) {
        cfg.routes.get("/api/people", roster::people, Gate.KEY_HELD);
        cfg.routes.get("/api/people/{id}/grants", roster::grants, Gate.KEY_HELD);
        cfg.routes.get("/api/people/{id}/payments", roster::paymentsOf, Gate.KEY_HELD);
        cfg.routes.get("/api/payments", roster::payments, Gate.KEY_HELD);
        cfg.routes.get("/api/payments/open", roster::openPayments, Gate.KEY_HELD);
        cfg.routes.get("/api/journal", roster::journal, Gate.KEY_HELD);

        // Each route writes one request into the bot's inbox, and the bot carries it out; see AccessApi.
        cfg.routes.post("/api/access/grant", access::grant, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/access/revoke", access::revoke, Gate.KEY_HELD, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/access/unlink", access::unlink, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/access/settle", access::settle, Gate.KEY_FRESH, Unjournaled.IN_THE_CHANGE);
        cfg.routes.post("/api/people/{id}/playtime", access::playtime, Gate.KEY_HELD, Unjournaled.IN_THE_CHANGE);

        cfg.routes.post(
                "/api/pack-exemptions/exempt",
                packExemptions::exempt,
                Gate.KEY_HELD,
                Journaled.as(JournalAction.EXEMPT_PACK));
        cfg.routes.post(
                "/api/pack-exemptions/enforce",
                packExemptions::enforce,
                Gate.KEY_HELD,
                Journaled.as(JournalAction.ENFORCE_PACK));
    }
}
