package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.steward.auth.Gate;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import io.javalin.security.Roles;
import io.javalin.security.RouteRole;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Fails the build on a route without a decision, using the routes the real service registered. */
class GateTest {

    private static Javalin app;

    @BeforeAll
    static void start() {
        app = RouteTable.start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void everyRouteCarriesExactlyOneDecision() {
        final List<String> undecided = new ArrayList<>();
        for (final Endpoint endpoint : endpoints()) {
            final long decided =
                    rolesOf(endpoint).stream().filter(Gate.class::isInstance).count();
            if (decided != 1) {
                undecided.add(endpoint.method + " " + endpoint.path + " carries " + decided);
            }
        }
        assertTrue(
                undecided.isEmpty(),
                "Every route in Steward has to say whether it needs a security key, and these do"
                        + " not - add a Gate value to the line that registers each of them:\n  "
                        + String.join("\n  ", undecided));
    }

    /**
     * Every writing route with its gate. {@link Gate#KEY_FRESH} marks only a change that needs the step-up.
     *
     * Such a change destroys, cannot be undone, grants access or money, or changes admins or keys.
     * A person edits this list to move a route across that line.
     * {@code POST /api/updates} is {@link Gate#KEY_HELD} here and asks for the step-up per kind of run.
     */
    private static final Map<String, Gate> WRITES_GATED = Map.ofEntries(
            Map.entry("POST /auth/logout", Gate.SIGNED_IN),
            // The key and app ceremonies issue the factor, so they cannot require it.
            Map.entry("POST /auth/webauthn/register/start", Gate.SIGNED_IN),
            Map.entry("POST /auth/webauthn/register/finish", Gate.SIGNED_IN),
            Map.entry("POST /auth/webauthn/authenticate/start", Gate.SIGNED_IN),
            Map.entry("POST /auth/webauthn/authenticate/finish", Gate.SIGNED_IN),
            Map.entry("POST /auth/totp/enrol/start", Gate.SIGNED_IN),
            Map.entry("POST /auth/totp/enrol/finish", Gate.SIGNED_IN),
            Map.entry("POST /auth/totp/verify", Gate.SIGNED_IN),
            // These routes replace or stop what runs, overwrite live data or run foreign code.
            Map.entry("POST /api/backups/{name}/restore", Gate.KEY_FRESH),
            Map.entry("POST /api/services/{name}/plugins", Gate.KEY_FRESH),
            Map.entry("DELETE /api/services/{name}/plugins/{artifact}", Gate.KEY_FRESH),
            Map.entry("POST /api/services/{name}/console", Gate.KEY_FRESH),
            // These routes change admins or keys.
            Map.entry("POST /api/admins/grant", Gate.KEY_FRESH),
            Map.entry("POST /api/admins/revoke", Gate.KEY_FRESH),
            Map.entry("DELETE /api/keys/{id}", Gate.KEY_FRESH),
            Map.entry("DELETE /api/authenticator", Gate.KEY_FRESH),
            // These routes grant access or money, or cut a link only the player can redo.
            Map.entry("POST /api/access/grant", Gate.KEY_FRESH),
            Map.entry("POST /api/access/unlink", Gate.KEY_FRESH),
            Map.entry("POST /api/access/settle", Gate.KEY_FRESH),
            // These changes cannot be undone: the season moving on, game progress, a public message.
            Map.entry("POST /api/season/phase", Gate.KEY_FRESH),
            Map.entry("POST /api/smp/objective", Gate.KEY_FRESH),
            Map.entry("POST /api/smp/milestone", Gate.KEY_FRESH),
            Map.entry("POST /api/announcements", Gate.KEY_FRESH),
            // These changes are easily undone or touch only the admin's session, notifications or preview.
            Map.entry("POST /api/updates", Gate.KEY_HELD),
            Map.entry("POST /api/updates/cancel", Gate.KEY_HELD),
            Map.entry("PUT /api/setting-groups/{service}/{name}", Gate.KEY_HELD),
            Map.entry("PUT /api/services/{name}/plugin-file", Gate.KEY_HELD),
            Map.entry("PUT /api/messages", Gate.KEY_HELD),
            Map.entry("PUT /api/documents/{namespace}/{name}", Gate.KEY_HELD),
            Map.entry("DELETE /api/documents/{namespace}/{name}", Gate.KEY_HELD),
            Map.entry("POST /api/document-images", Gate.KEY_HELD),
            Map.entry("PUT /api/shared-parts/{name}", Gate.KEY_HELD),
            Map.entry("DELETE /api/shared-parts/{name}", Gate.KEY_HELD),
            Map.entry("POST /api/shared-parts/{name}/changes", Gate.KEY_HELD),
            Map.entry("POST /api/access/revoke", Gate.KEY_HELD),
            Map.entry("POST /api/people/{id}/playtime", Gate.KEY_HELD),
            Map.entry("POST /api/pack-exemptions/exempt", Gate.KEY_HELD),
            Map.entry("POST /api/pack-exemptions/enforce", Gate.KEY_HELD),
            Map.entry("POST /api/season/date", Gate.KEY_HELD),
            Map.entry("POST /api/hunger-games/start", Gate.KEY_HELD),
            Map.entry("PUT /api/keys/{id}", Gate.KEY_HELD),
            Map.entry("DELETE /api/step-up", Gate.KEY_HELD),
            Map.entry("POST /api/web-push/subscribe", Gate.KEY_HELD),
            Map.entry("DELETE /api/web-push/subscribe", Gate.KEY_HELD),
            Map.entry("POST /api/web-push/test", Gate.KEY_HELD),
            Map.entry("PUT /api/alerts/preferences", Gate.KEY_HELD),
            Map.entry("POST /api/message-preview", Gate.KEY_HELD),
            Map.entry("POST /api/document-preview", Gate.KEY_HELD),
            Map.entry("POST /api/documents/render", Gate.KEY_HELD));

    @Test
    void everyWriteCarriesTheGateTheAuditGaveIt() {
        final Map<String, String> gated = new TreeMap<>();
        for (final Endpoint endpoint : endpoints()) {
            if (WRITES.contains(endpoint.method)) {
                gated.put(endpoint.method + " " + endpoint.path, gatesOf(endpoint));
            }
        }
        final Map<String, String> expected = new TreeMap<>();
        WRITES_GATED.forEach((route, gate) -> expected.put(route, "[" + gate + "]"));
        assertEquals(
                expected,
                gated,
                "A writing route carries the gate WRITES_GATED names for it, and every writing route is named there.");
    }

    /** Reading needs at least {@link Gate#KEY_HELD}, except five routes that a signed-out browser needs. */
    @Test
    void theOnlyReadsOutsideTheKeyAreTheDoorItself() {
        final Set<String> allowed = Set.of(
                "GET /api/health",
                "GET /api/me",
                // The sign-in page reads the page's words, which its script ships anyway.
                "GET /api/texts",
                "GET /auth/login",
                "GET /auth/callback",
                // These routes answer "nothing at this address" and nothing else.
                "GET /api/<path>",
                "GET /auth/<path>");

        final List<String> loose = new ArrayList<>();
        for (final Endpoint endpoint : endpoints()) {
            if (!endpoint.method.equals(HandlerType.GET)) {
                continue;
            }
            final String named = endpoint.method + " " + endpoint.path;
            final boolean open = rolesOf(endpoint).contains(Gate.ANYONE);
            if (open && !allowed.contains(named)) {
                loose.add(named + " is open to anybody");
            }
        }
        assertTrue(
                loose.isEmpty(),
                "A reading route open to anybody shows a stranger something. These are new:\n  "
                        + String.join("\n  ", loose));
    }

    /** Checks that the routing table is not empty. Every other assertion here would pass on an empty one. */
    @Test
    void thereAreRoutesToCheckAtAll() {
        assertTrue(
                endpoints().size() >= 40,
                "Steward has about forty routes; this found " + endpoints().size()
                        + ", so the way they are enumerated has stopped working.");
        assertEquals(
                EnumSet.allOf(Gate.class),
                endpoints().stream()
                        .flatMap(endpoint -> rolesOf(endpoint).stream())
                        .filter(Gate.class::isInstance)
                        .map(Gate.class::cast)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(Gate.class))),
                "All four values are meant to be in use. One that is not is either a value nothing"
                        + " needs, or a set of routes that quietly moved off it.");
    }

    private static final Set<HandlerType> WRITES =
            Set.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE);

    @Test
    void everyEndpointLivesUnderOneOfTheTwoPrefixes() {
        // `Gatekeeper#isOurs` assumes that every endpoint lives under /api or /auth.
        final List<String> outside = new ArrayList<>();
        for (final Endpoint endpoint : endpoints()) {
            final String path = endpoint.path;
            if (!path.startsWith("/api/")
                    && !path.startsWith("/auth/")
                    && !path.equals("/api")
                    && !path.equals("/auth")) {
                outside.add(endpoint.method + " " + path);
            }
        }
        assertTrue(
                outside.isEmpty(),
                "these endpoints sit outside /api and /auth, so Gatekeeper#isOurs would hand them "
                        + "ANYONE instead of refusing them: " + outside);
    }

    private static List<Endpoint> endpoints() {
        return RouteTable.endpoints(app);
    }

    private static Set<RouteRole> rolesOf(final Endpoint endpoint) {
        final Roles roles = endpoint.metadata(Roles.class);
        return roles == null ? Set.of() : Set.copyOf(roles.getRoles());
    }

    private static String gatesOf(final Endpoint endpoint) {
        return rolesOf(endpoint).stream()
                .filter(Gate.class::isInstance)
                .map(Object::toString)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
