package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.steward.docker.Console;
import eu.nordtal.s2.steward.docker.Docker;
import eu.nordtal.s2.steward.docker.DockerOps;
import eu.nordtal.s2.steward.host.HostMetrics;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinGson;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

/** {@link StackApi}'s routes on a bare Javalin against the real daemon: no gate in front, and a caller who stays. */
final class StackServer {

    static final String PROJECT = "nordtal-s2";

    /** Signed in for as long as the test runs; the gate in front of these routes is {@code GateTest}'s to check. */
    private static final Caller ALWAYS = new Caller() {
        @Override
        public String name(final Context ctx) {
            return "a test (1)";
        }

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

    /** The API under test, with backups and configs in {@code /tmp} and a nightly window that never fires. */
    static StackApi api(final Docker docker) {
        return new StackApi(
                docker,
                new DockerOps(docker, PROJECT),
                new Console(docker, PROJECT),
                new HostMetrics(),
                PROJECT,
                Path.of("/tmp"),
                Path.of("/tmp"),
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
                    api.register(cfg, ALWAYS);
                })
                .start(port);
    }
}
