package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.steward.api.FakeDirectories;
import eu.nordtal.s2.steward.api.StackApi;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.config.WebSpec;
import eu.nordtal.s2.steward.docker.Console;
import eu.nordtal.s2.steward.docker.Docker;
import eu.nordtal.s2.steward.docker.DockerOps;
import eu.nordtal.s2.steward.docker.DockerSocket;
import eu.nordtal.s2.steward.host.HostMetrics;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/** The routes the real service registers, asked of a running one rather than read out of its source. */
final class RouteTable {

    private static final String PROJECT = "nordtal-s2";

    private RouteTable() {}

    /** Starts the service with no database and no daemon: every route is registered before either is asked. */
    static Javalin start() {
        // Every section at its default; jcore's @ConfigSpec leaves the getters abstract otherwise.
        final WebSpec config = new WebSpec() {
            @Override
            public DiscordSpec discord() {
                return new DiscordSpec() {};
            }

            @Override
            public AlertSpec alerts() {
                return new AlertSpec() {};
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
        // A socket nobody listens on: registering a route asks the daemon nothing.
        final Docker docker = new Docker(new DockerSocket(Path.of("/nonexistent/docker.sock"), Duration.ofSeconds(1)));
        final StackApi stack = new StackApi(
                docker,
                new DockerOps(docker, PROJECT),
                new Console(docker, PROJECT),
                new HostMetrics(),
                PROJECT,
                Path.of("/nonexistent"),
                Path.of("/nonexistent"),
                FakeDirectories.updates(),
                FakeDirectories.audit(),
                new StackApi.Nightly("04:45", List.of(), "05:15", List.of(), ZoneId.of("Europe/Berlin")),
                Clock.systemUTC());
        return new Web(
                        config,
                        new DiscordAuth(config.discord(), config.publicUrl()),
                        stack,
                        new InternalClient("steward-agent", "http://127.0.0.1:1", "", Duration.ofSeconds(1)),
                        false,
                        null,
                        Clock.systemUTC())
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
