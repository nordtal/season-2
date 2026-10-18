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

/** Fails the build on a route nobody decided about, asking the real service what Javalin routed. */
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

    /** Every route Javalin has, with the decision it carries; none is undecided. */
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
     * Every writing route with its gate, {@link Gate#KEY_FRESH} only for a change that needs the step-up.
     *
     * That is one that destroys, cannot be undone, hands out access or money, or changes admins or keys.
     * Written out rather than derived, so moving a route across that line means editing a list a person reads.
     * {@code POST /api/updates} is {@link Gate#KEY_HELD} here and asks for the step-up per kind of run.
     */
    private static final Map<String, Gate> WRITES_GATED = Map.ofEntries(
            // Signing out: a person part-way through the key ceremony must still be able to leave.
            Map.entry("POST /auth/logout", Gate.SIGNED_IN),
            // The ceremonies of a key and of an app: a door cannot ask for what it hands out.
            Map.entry("POST /auth/webauthn/register/start", Gate.SIGNED_IN),
            Map.entry("POST /auth/webauthn/register/finish", Gate.SIGNED_IN),
            Map.entry("POST /auth/webauthn/authenticate/start", Gate.SIGNED_IN),
            Map.entry("POST /auth/webauthn/authenticate/finish", Gate.SIGNED_IN),
            Map.entry("POST /auth/totp/enrol/start", Gate.SIGNED_IN),
            Map.entry("POST /auth/totp/enrol/finish", Gate.SIGNED_IN),
            Map.entry("POST /auth/totp/verify", Gate.SIGNED_IN),
            // Replaces or stops what runs, overwrites live data, or runs foreign code.
            Map.entry("POST /api/backups/{name}/restore", Gate.KEY_FRESH),
            Map.entry("POST /api/services/{name}/plugins", Gate.KEY_FRESH),
            Map.entry("DELETE /api/services/{name}/plugins/{artifact}", Gate.KEY_FRESH),
            Map.entry("POST /api/services/{name}/console", Gate.KEY_FRESH),
            // Changes admins or keys.
            Map.entry("POST /api/admins/grant", Gate.KEY_FRESH),
            Map.entry("POST /api/admins/revoke", Gate.KEY_FRESH),
            Map.entry("DELETE /api/keys/{id}", Gate.KEY_FRESH),
            Map.entry("DELETE /api/authenticator", Gate.KEY_FRESH),
            // Hands out access or money, or cuts a link only the player can redo.
            Map.entry("POST /api/access/grant", Gate.KEY_FRESH),
            Map.entry("POST /api/access/unlink", Gate.KEY_FRESH),
            Map.entry("POST /api/access/settle", Gate.KEY_FRESH),
            // Cannot be taken back: the season moving on, game progress, a public message.
            Map.entry("POST /api/season/phase", Gate.KEY_FRESH),
            Map.entry("POST /api/smp/objective", Gate.KEY_FRESH),
            Map.entry("POST /api/smp/milestone", Gate.KEY_FRESH),
            Map.entry("POST /api/announcements", Gate.KEY_FRESH),
            // Easily undone, or touching only one's own session, notifications or preview.
            Map.entry("POST /api/updates", Gate.KEY_HELD),
            Map.entry("POST /api/updates/cancel", Gate.KEY_HELD),
            Map.entry("PUT /api/setting-groups/{service}/{name}", Gate.KEY_HELD),
            Map.entry("PUT /api/messages", Gate.KEY_HELD),
            Map.entry("PUT /api/layouts/{bundle}/{layout}", Gate.KEY_HELD),
            Map.entry("DELETE /api/layouts/{bundle}/{layout}", Gate.KEY_HELD),
            Map.entry("POST /api/layout-images", Gate.KEY_HELD),
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
            Map.entry("POST /api/message-preview", Gate.KEY_HELD));

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

    /** Reading is at least {@link Gate#KEY_HELD}, with the five routes a signed-out browser needs. */
    @Test
    void theOnlyReadsOutsideTheKeyAreTheDoorItself() {
        final Set<String> allowed = Set.of(
                // A container healthcheck carries no cookie.
                "GET /api/health",
                // The one route whose answer IS "you are not signed in".
                "GET /api/me",
                // The page's own words, which its script ships anyway, so the sign-in page reads them too.
                "GET /api/texts",
                // The two halves of the Discord redirect.
                "GET /auth/login",
                "GET /auth/callback",
                // Answer "nothing at this address" and nothing else, no more than a closed door shows a stranger.
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

    /** The routing table is not empty, which every other assertion here would pass on. */
    @Test
    void thereAreRoutesToCheckAtAll() {
        // A GateTest that enumerates nothing is green about nothing: an empty list passes every assertion.
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

    /** What Javalin actually routed, filters and internal entries left out. */
    @Test
    void everyEndpointLivesUnderOneOfTheTwoPrefixes() {
        // The premise `Gatekeeper#isOurs` rests on: every endpoint lives under /api or /auth.
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
