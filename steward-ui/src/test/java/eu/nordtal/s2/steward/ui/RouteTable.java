package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
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
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** The routes the real service registers, asked of a running one rather than read out of its source. */
final class RouteTable {

    private RouteTable() {}

    /** Starts the service with no database: every route is registered before {@code Data} is asked. */
    static Javalin start() {
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
        return new StewardUi(
                        config,
                        new DiscordAuth(config.discord(), config.publicUrl()),
                        new InternalClient("steward-worker", "http://127.0.0.1:1", "", Duration.ofSeconds(1)),
                        new InternalClient("steward-deployer", "http://127.0.0.1:1", "", Duration.ofSeconds(1)),
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
