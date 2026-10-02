package eu.nordtal.s2.stewardagent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.InternalServer;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.stewardagent.docker.Containers;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerSocket;
import eu.nordtal.s2.stewardagent.docker.FakeDaemon;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * steward-agent's real routes over a {@link FakeDaemon}, behind the real guard, on a port of the test's choosing.
 *
 * The deployments are left to {@code more}, since a real one would run compose against the host.
 */
public final class AgentStandIn implements AutoCloseable {

    public static final String TOKEN = "agent-token";

    /** compose.yml as the stand-in reads it: {@code smp} with a console, stopped for a backup, {@code postgres} not. */
    public static final String SERVICES = """
            {"smp":{"image":"ghcr.io/nordtal/minecraft:latest",
                    "labels":{"eu.nordtal.console":"true","eu.nordtal.backup":"stop"}},
             "steward-agent":{"image":"ghcr.io/nordtal/steward-agent:latest",
                    "volumes":[{"type":"bind","source":"/srv/mc-smp","target":"%s/nordtal-s2_mc-smp"}]},
             "postgres":{"image":"postgres:18"}}
            """;

    public final FakeDaemon daemon;

    /** Where archives are listed from and downloaded out of. */
    public final Path backups;

    /** One directory per service, where the message bundles' jars and override files are looked for. */
    public final Path configs;

    /** One directory per service's volume, for its size and its rotated logs. */
    public final Path volumes;

    private final AgentApi api;
    private final Javalin server;

    /**
     * Starts on {@code port}, or any free one for {@code 0}.
     *
     * @param more routes beside the real ones, such as stand-in deployments
     */
    public AgentStandIn(final Path scratch, final int port, final Consumer<JavalinConfig> more) throws IOException {
        this.daemon = new FakeDaemon(scratch);
        this.backups = Files.createDirectories(scratch.resolve("backups"));
        this.configs = Files.createDirectories(scratch.resolve("configs"));
        this.volumes = Files.createDirectories(scratch.resolve("volumes"));
        final Path sources = Files.createDirectories(scratch.resolve("sources"));
        final JsonObject services =
                JsonParser.parseString(SERVICES.formatted(sources)).getAsJsonObject();
        this.api = new AgentApi(
                new Docker(new DockerSocket(daemon.socket(), Duration.ofSeconds(5))),
                FakeDaemon.PROJECT,
                () -> services,
                Containers.Definitions.NONE,
                new AgentApi.Paths(volumes, configs, sources, backups),
                Clock.systemUTC());
        api.start();
        final String prefix = "NORDTAL_STEWARD_AGENT_";
        this.server = new InternalServer(
                        AgentWire.SERVICE, Map.of(prefix + "TOKEN", TOKEN, prefix + "PORT", String.valueOf(port))::get)
                .start(AgentWire.PORT, config -> {
                    api.register(config);
                    more.accept(config);
                });
    }

    public String base() {
        return "http://127.0.0.1:" + server.port();
    }

    /** A client holding the right secret. */
    public InternalClient client() {
        return new InternalClient(AgentWire.SERVICE, base(), TOKEN, Duration.ofSeconds(10));
    }

    @Override
    public void close() throws IOException {
        api.close();
        server.stop();
        daemon.close();
    }
}
