package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Gate;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.AlertSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.DeployerSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.DiscordSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.WebAuthnSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.WebPushSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.WorkerSpec;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import io.javalin.security.Roles;
import io.javalin.security.RouteRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Fails the build on a route nobody decided about, asking the real service what Javalin routed. */
class GateTest {

    private static Javalin app;

    /** The service with no database, which is enough since every route is registered before {@code Data} is asked. */
    @BeforeAll
    static void start() {
        // Every section at its default; jcore's @ConfigSpec leaves the getters abstract otherwise.
        final UiSpec config = new UiSpec() {
            @Override
            public WorkerSpec worker() {
                return new WorkerSpec() {};
            }

            @Override
            public DiscordSpec discord() {
                return new DiscordSpec() {};
            }

            @Override
            public AlertSpec alerts() {
                return new AlertSpec() {};
            }

            @Override
            public DeployerSpec deployer() {
                return new DeployerSpec() {};
            }

            @Override
            public WebAuthnSpec webauthn() {
                return new WebAuthnSpec() {};
            }

            @Override
            public AvatarSpec avatars() {
                return new AvatarSpec() {};
            }

            @Override
            public WebPushSpec webPush() {
                return new WebPushSpec() {};
            }
        };
        app = new StewardUi(
                        config,
                        new DiscordAuth(config.discord(), config.publicUrl()),
                        new InternalClient("steward-worker", "http://127.0.0.1:1", "", Duration.ofSeconds(1)),
                        new InternalClient("steward-deployer", "http://127.0.0.1:1", "", Duration.ofSeconds(1)),
                        null)
                // Port 0: the OS picks a free one, so this does not collide with another instance running.
                .start(0);
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
     * Writing is {@link Gate#KEY_FRESH}, with exactly three named exceptions.
     *
     * The exceptions are written out rather than derived, so adding a fourth means editing a list a person reads.
     */
    @Test
    void theOnlyWritesOutsideTheKeyAreTheOnesThatHandItOut() {
        final Set<String> allowed = Set.of(
                // Signing out: a person part-way through the key ceremony must still be able to leave.
                "POST /auth/logout",
                // The two ceremonies: a door cannot ask for the key it exists to hand out.
                "POST /auth/webauthn/register/start",
                "POST /auth/webauthn/register/finish",
                "POST /auth/webauthn/authenticate/start",
                "POST /auth/webauthn/authenticate/finish");

        final List<String> loose = new ArrayList<>();
        for (final Endpoint endpoint : endpoints()) {
            if (!WRITES.contains(endpoint.method)) {
                continue;
            }
            final String named = endpoint.method + " " + endpoint.path;
            final boolean fresh = rolesOf(endpoint).contains(Gate.KEY_FRESH);
            if (!fresh && !allowed.contains(named)) {
                loose.add(named + " is " + gatesOf(endpoint));
            }
            if (fresh && allowed.contains(named)) {
                loose.add(named + " is in the exception list AND behind the key - decide which");
            }
        }
        assertTrue(
                loose.isEmpty(),
                "Every writing route needs the key held in the last five minutes, unless it is one"
                        + " of the five that hand the key out. These are neither:\n  "
                        + String.join("\n  ", loose));
    }

    /** Reading is at least {@link Gate#KEY_HELD}, with the four routes a signed-out browser needs. */
    @Test
    void theOnlyReadsOutsideTheKeyAreTheDoorItself() {
        final Set<String> allowed = Set.of(
                // A container healthcheck carries no cookie.
                "GET /api/health",
                // The one route whose answer IS "you are not signed in".
                "GET /api/me",
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
        // The premise `StewardUi#isOurs` rests on: every endpoint lives under /api or /auth.
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
                "these endpoints sit outside /api and /auth, so StewardUi#isOurs would hand them "
                        + "ANYONE instead of refusing them: " + outside);
    }

    private static List<Endpoint> endpoints() {
        return app.unsafe.internalRouter.allHttpHandlers().stream()
                .map(parsed -> parsed.endpoint)
                // BEFORE and AFTER are filters with no decision of their own; `guard` is one of them.
                .filter(endpoint -> !endpoint.method.equals(HandlerType.BEFORE)
                        && !endpoint.method.equals(HandlerType.BEFORE_MATCHED)
                        && !endpoint.method.equals(HandlerType.AFTER)
                        && !endpoint.method.equals(HandlerType.AFTER_MATCHED))
                .toList();
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
