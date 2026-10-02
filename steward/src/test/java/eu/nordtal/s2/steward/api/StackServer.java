package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.steward.web.ErrorHandlers;
import eu.nordtal.s2.stewardagent.AgentStandIn;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinGson;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

/** {@link StackApi}'s routes and the error shapes over a stand-in agent: no gate in front, and a caller who stays. */
final class StackServer {

    /** Signed in for as long as the test runs; the gate in front of these routes is {@code GateTest}'s to check. */
    private static final Caller ALWAYS = new Caller() {
        @Override
        public Actor actor(final Context ctx) {
            return Actor.STEWARD;
        }

        @Override
        public boolean stillSignedIn(final Context ctx) {
            return true;
        }
    };

    private StackServer() {}

    /** The API under test over {@code agent}, with a nightly window that never fires. */
    static StackApi api(final AgentStandIn agent) {
        return new StackApi(
                new AgentClient(agent.client()),
                FakeDirectories.updates(),
                FakeDirectories.audit(),
                new StackApi.Nightly(
                        "04:45",
                        List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"),
                        "05:15",
                        List.of(),
                        ZoneId.of("Europe/Berlin")),
                Clock.systemUTC());
    }

    /** Serves {@code api} on {@code port}; stop the returned server, then close the API. */
    static Javalin start(final StackApi api, final int port) {
        return Javalin.create(cfg -> {
                    cfg.jsonMapper(new JavalinGson(Json.gson(), true));
                    cfg.startup.showJavalinBanner = false;
                    ErrorHandlers.install(cfg);
                    api.register(cfg, ALWAYS);
                })
                .start(port);
    }
}
