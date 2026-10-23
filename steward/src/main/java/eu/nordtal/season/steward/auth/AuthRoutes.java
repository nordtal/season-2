package eu.nordtal.season.steward.auth;

import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.steward.journal.Journaled;
import eu.nordtal.season.steward.journal.Unjournaled;
import io.javalin.config.JavalinConfig;

/** Registers the routes of signing in, the second factor, the admin's own profile and the admin list. */
public final class AuthRoutes {

    private AuthRoutes() {}

    public static void register(
            final JavalinConfig cfg,
            final AuthFlow authFlow,
            final SecondFactor secondFactor,
            final AuthenticatorApp authenticatorApp,
            final Profile profile,
            final AdminApi admins) {
        cfg.routes.get("/api/me", profile::whoAmI, Gate.ANYONE);
        cfg.routes.get("/auth/login", authFlow::login, Gate.ANYONE);
        cfg.routes.get("/auth/callback", authFlow::callback, Gate.ANYONE);
        // SameSite=Lax allows a top-level POST, so the CSRF token check applies here too.
        cfg.routes.post("/auth/logout", authFlow::logout, Gate.SIGNED_IN, Unjournaled.OWN_SESSION);
        keyRoutes(cfg, secondFactor);
        authenticatorApp.register(cfg);
        // The example values depend on the asking admin.
        cfg.routes.get("/api/message-examples", profile::messageExamples, Gate.KEY_HELD);
        // AdminApi writes the admin grant directly, not the bot.
        cfg.routes.post("/api/admins/grant", admins::grant, Gate.KEY_FRESH, Journaled.as(JournalAction.GRANT_ADMIN));
        cfg.routes.post("/api/admins/revoke", admins::revoke, Gate.KEY_FRESH, Journaled.as(JournalAction.REVOKE_ADMIN));
    }

    private static void keyRoutes(final JavalinConfig cfg, final SecondFactor secondFactor) {
        // These routes issue the key, so they cannot require it.
        cfg.routes.post(
                "/auth/webauthn/register/start",
                secondFactor::beginRegistration,
                Gate.SIGNED_IN,
                Unjournaled.CEREMONY_START);
        cfg.routes.post(
                "/auth/webauthn/register/finish",
                secondFactor::finishRegistration,
                Gate.SIGNED_IN,
                Journaled.as(JournalAction.REGISTER_KEY));
        cfg.routes.post(
                "/auth/webauthn/authenticate/start",
                secondFactor::beginAssertion,
                Gate.SIGNED_IN,
                Unjournaled.CEREMONY_START);
        cfg.routes.post(
                "/auth/webauthn/authenticate/finish",
                secondFactor::finishAssertion,
                Gate.SIGNED_IN,
                Journaled.as(JournalAction.HELD_KEY));

        // The key list is part of /api/me, not a route of its own.
        cfg.routes.put(
                "/api/keys/{id}", secondFactor::renameKey, Gate.KEY_HELD, Journaled.as(JournalAction.RENAME_KEY));
        cfg.routes.delete(
                "/api/keys/{id}", secondFactor::removeKey, Gate.KEY_FRESH, Journaled.as(JournalAction.REMOVE_KEY));
        cfg.routes.delete("/api/step-up", secondFactor::endStepUp, Gate.KEY_HELD, Unjournaled.OWN_SESSION);
    }
}
