package eu.nordtal.season.steward;

import eu.nordtal.season.common.time.TestScheduler;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.steward.alert.Thresholds;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.stack.FakeDirectories;
import eu.nordtal.season.steward.stack.StackApi;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/** The routes the real service registers, asked of a running one rather than read out of its source. */
final class RouteTable {

    private RouteTable() {}

    /** Starts the service with no database and no agent: every route is registered before either is asked. */
    static Javalin start() {
        // Every section at its default; a @ConfigSpec leaves the getters abstract otherwise.
        final WebSpec config = new WebSpec() {
            @Override
            public DiscordSpec discord() {
                return new DiscordSpec() {};
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
        // An agent nobody runs: registering a route asks it nothing.
        final AgentClient agent =
                new AgentClient(new InternalClient("steward-agent", "http://127.0.0.1:1", "", Duration.ofSeconds(1)));
        final StackApi stack = new StackApi(
                agent,
                FakeDirectories.updates(),
                FakeDirectories.audit(),
                new StackApi.Nightly("04:45", List.of(), "05:15", List.of(), ZoneId.of("Europe/Berlin")),
                Clock.systemUTC(),
                TestScheduler.SHARED);
        return new Web(
                        config,
                        () -> new Thresholds(85, 90, 36),
                        new DiscordAuth(config.discord(), config.publicUrl()),
                        stack,
                        agent,
                        false,
                        null,
                        NetworkSettings.defaultLanguages(),
                        Clock.systemUTC(),
                        TestScheduler.SHARED)
                // Port 0: the OS picks a free one, so this does not collide with another instance running.
                .start(0);
    }

    /** Returns every endpoint of the running service, without the filters, which decide nothing of their own. */
    static List<Endpoint> endpoints(final Javalin app) {
        return app.unsafe.internalRouter.allHttpHandlers().stream()
                .map(parsed -> parsed.endpoint)
                // BEFORE and AFTER are filters with no decision of their own; `guard` is one of them.
                .filter(endpoint -> !endpoint.method.equals(HandlerType.BEFORE)
                        && !endpoint.method.equals(HandlerType.BEFORE_MATCHED)
                        && !endpoint.method.equals(HandlerType.AFTER)
                        && !endpoint.method.equals(HandlerType.AFTER_MATCHED))
                .toList();
    }
}
