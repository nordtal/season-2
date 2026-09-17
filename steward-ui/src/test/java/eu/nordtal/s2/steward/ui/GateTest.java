package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Gate;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.AlertSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.DeployerSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.DiscordSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.WebAuthnSpec;
import eu.nordtal.s2.steward.ui.config.UiSpec.WorkerSpec;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import io.javalin.security.Roles;
import io.javalin.security.RouteRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A route that nobody decided about breaks the build.
 *
 * <h2>This is the test the whole of package E exists for</h2>
 * Everything else in the second factor is a check that runs when somebody calls a route. This one
 * runs when somebody <em>writes</em> one - and it is the only part of the arrangement that survives
 * the next person, who will add an endpoint six months from now without having read any of it. A
 * list of dangerous paths kept somewhere else would go quietly out of date on that day; this goes
 * red.
 *
 * <p><b>Seen red before it was committed</b> (2026-09-14): a {@code cfg.routes.post} was added with
 * no {@link Gate}, this test named it and failed, and the route was taken out again. That is the
 * evidence the ticket asks for, and it is written here because the run itself leaves no trace.</p>
 *
 * <h2>Why it starts the service rather than reading the source</h2>
 * A regular expression over {@code StewardUi.java} would be a second opinion about what a route is,
 * and it would miss every route registered anywhere else - {@code CommandApi}, {@code ConfigApi} and
 * {@code DeployerApi} hand out handlers today and could hand out registrations tomorrow. What is
 * asked here is what Javalin ACTUALLY ROUTED, which is the only list that cannot be wrong.
 */
class GateTest {

    private static Javalin app;

    /**
     * The service with no database at all.
     *
     * <p>Every route is registered before anything is asked of {@code Data}, which is what makes
     * this cheap: no Postgres, no Docker, no migrations - just the routing table. The nulls are the
     * documented "a test about the proxy" shape of the constructor.</p>
     */
    @BeforeAll
    static void start() {
        // EVERY SECTION AT ITS DEFAULT. Six anonymous implementations rather than one, because
        // jcore's @ConfigSpec leaves the section getters abstract - the defaults live on the
        // sections themselves. Nothing here is read by a route; the service builds a RelyingParty
        // and two clients at startup and would refuse to start without them.
        final UiSpec config = new UiSpec() {
            @Override
            public WorkerSpec worker() {
                return new WorkerSpec() {
                };
            }

            @Override
            public DiscordSpec discord() {
                return new DiscordSpec() {
                };
            }

            @Override
            public AlertSpec alerts() {
                return new AlertSpec() {
                };
            }

            @Override
            public DeployerSpec deployer() {
                return new DeployerSpec() {
                };
            }

            @Override
            public WebAuthnSpec webauthn() {
                return new WebAuthnSpec() {
                };
            }

            @Override
            public AvatarSpec avatars() {
                return new AvatarSpec() {
                };
            }
        };
        app = new StewardUi(config,
                new DiscordAuth(config.discord(), config.publicUrl()),
                new InternalClient("steward-worker", "http://127.0.0.1:1", "", Duration.ofSeconds(1)),
                new InternalClient("steward-deployer", "http://127.0.0.1:1", "", Duration.ofSeconds(1)),
                null)
                // Port 0: the operating system picks a free one. A fixed port here would be a test
                // that fails when somebody runs two of them, or the interface, at the same time.
                .start(0);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
    }

    /** Every route Javalin has, with the decision it carries - and there are no undecided ones. */
    @Test
    void everyRouteCarriesExactlyOneDecision() {
        final List<String> undecided = new ArrayList<>();
        for (final Endpoint endpoint : endpoints()) {
            final long decided = rolesOf(endpoint).stream().filter(Gate.class::isInstance).count();
            if (decided != 1) {
                undecided.add(endpoint.method + " " + endpoint.path + " carries " + decided);
            }
        }
        assertTrue(undecided.isEmpty(),
                "Every route in Steward has to say whether it needs a security key, and these do"
                        + " not - add a Gate value to the line that registers each of them:\n  "
                        + String.join("\n  ", undecided));
    }

    /**
     * Writing is {@link Gate#KEY_FRESH}, with exactly three named exceptions.
     *
     * <p>This is the half of package E that {@link #everyRouteCarriesExactlyOneDecision} cannot
     * see: a new {@code POST} <em>with</em> a decision is fine by that test whatever the decision
     * says, and "somebody chose ANYONE because it was quicker" is the failure this one is for. The
     * exceptions are written out here rather than derived, so adding a fourth means editing a list
     * that a person has to look at.</p>
     */
    @Test
    void theOnlyWritesOutsideTheKeyAreTheOnesThatHandItOut() {
        final Set<String> allowed = Set.of(
                // Signing out: a person part-way through the key ceremony must still be able to
                // leave, and a sign-out behind the key would be a locked room.
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
        assertTrue(loose.isEmpty(),
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
                // The two that answer "there is nothing at this address" and nothing else. They
                // exist because the single-page fallback claimed every unmatched GET, /api
                // included, and carried no Gate - so a mistyped endpoint came back as a 500 about
                // a route nobody wrote. These show a stranger no more than a closed door does.
                "GET /api/<path>",
                "GET /auth/<path>");

        final List<String> loose = new ArrayList<>();
        for (final Endpoint endpoint : endpoints()) {
            if (endpoint.method != HandlerType.GET) {
                continue;
            }
            final String named = endpoint.method + " " + endpoint.path;
            final boolean open = rolesOf(endpoint).contains(Gate.ANYONE);
            if (open && !allowed.contains(named)) {
                loose.add(named + " is open to anybody");
            }
        }
        assertTrue(loose.isEmpty(),
                "A reading route open to anybody shows a stranger something. These are new:\n  "
                        + String.join("\n  ", loose));
    }

    /** The routing table is not empty - which is what this whole class would otherwise pass on. */
    @Test
    void thereAreRoutesToCheckAtAll() {
        // A GateTest that enumerates nothing is a GateTest that is green about nothing, and that is
        // exactly how a check of this shape dies: somebody changes how routes are registered, the
        // list comes back empty, and three assertions over an empty list all pass.
        assertTrue(endpoints().size() >= 40,
                "Steward has about forty routes; this found " + endpoints().size()
                        + ", so the way they are enumerated has stopped working.");
        assertEquals(EnumSet.allOf(Gate.class),
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
        // THE PREMISE `StewardUi#isOurs` RESTS ON, AND IT IS A PREMISE, NOT A FACT OF NATURE.
        //
        // `guard` reads a Gate off the route it is about to run. Two things reach it that cannot
        // carry one: the static bundle and the single-page fallback. `isOurs` tells them apart
        // from an endpoint by path prefix and hands them ANYONE - which is right only as long as
        // every endpoint really does live under /api or /auth.
        //
        // The day somebody registers /webhooks/bunq without a Gate, that route would be handed
        // ANYONE in silence instead of refused. This test is what makes that day loud. If the new
        // route is deliberate, `isOurs` is the thing to change, and it says so in its own javadoc.
        final List<String> outside = new ArrayList<>();
        for (final Endpoint endpoint : endpoints()) {
            final String path = endpoint.path;
            if (!path.startsWith("/api/") && !path.startsWith("/auth/")
                    && !path.equals("/api") && !path.equals("/auth")) {
                outside.add(endpoint.method + " " + path);
            }
        }
        assertTrue(outside.isEmpty(),
                "these endpoints sit outside /api and /auth, so StewardUi#isOurs would hand them "
                        + "ANYONE instead of refusing them: " + outside);
    }

    private static List<Endpoint> endpoints() {
        return app.unsafe.internalRouter.allHttpHandlers().stream()
                .map(parsed -> parsed.endpoint)
                // BEFORE and AFTER are filters and carry no decision of their own - `guard` IS one
                // of them, and asking it to declare a Gate would be asking the door what it needs
                // to get through itself.
                .filter(endpoint -> endpoint.method != HandlerType.BEFORE
                        && endpoint.method != HandlerType.BEFORE_MATCHED
                        && endpoint.method != HandlerType.AFTER
                        && endpoint.method != HandlerType.AFTER_MATCHED)
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
