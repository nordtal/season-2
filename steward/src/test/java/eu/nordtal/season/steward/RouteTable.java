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

/** The routes of a running service, not read from its source. */
final class RouteTable {

    private RouteTable() {}

    /** Starts the service with no database and no agent. It registers every route before it asks for either. */
    static Javalin start() {
        // Every section has its default, because a @ConfigSpec leaves the getters abstract otherwise.
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
                .start(0);
    }

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
