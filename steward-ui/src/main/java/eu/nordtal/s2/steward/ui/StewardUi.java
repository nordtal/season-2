package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.json.JavalinGson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Nordtal Steward - the web interface.
 *
 * <p><b>This process never touches Docker.</b> §3 of the concept draws that line and this module
 * keeps it: the container runs without the socket, and everything about a container - state, logs,
 * a console line, an image digest - is a call to {@code steward-worker}'s internal API. Creating
 * containers is {@code steward-deployer}'s, and nobody else's.</p>
 */
public final class StewardUi {

    private static final Logger log = LoggerFactory.getLogger(StewardUi.class);

    /** Behind Caddy, so plain HTTP on the steward network is the whole story here. */
    private static final int DEFAULT_PORT = 8080;

    private StewardUi() {
    }

    public static void main(String[] args) {
        int port = port();
        Javalin app = Javalin.create(config -> {
            // gson, not jackson: both are `optional` in Javalin's POM, and jcore already exports
            // gson. A second databind on this classpath is how the repo once ended up with two Gson
            // types that were not each other.
            config.jsonMapper(new JavalinGson(new Gson(), true));
            config.startup.showJavalinBanner = false;

            // Javalin 7 declares routes on the config rather than on the started instance, so
            // every endpoint of this service is reachable from here downwards and nowhere else.
            //
            // The compose healthcheck asks this one and nothing else. It answers for the web layer
            // only - whether the worker or the database can be reached is something the interface
            // shows, not something that decides whether this container is healthy. A UI that calls
            // itself unhealthy because the thing it watches is broken is a UI nobody can open at
            // the moment they need it.
            config.routes.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
        });

        app.start(port);
        log.info("Nordtal Steward listening on {}", port);
    }

    private static int port() {
        String value = System.getenv("NORDTAL_STEWARD_UI_PORT");
        return value == null || value.isBlank() ? DEFAULT_PORT : Integer.parseInt(value.trim());
    }
}
