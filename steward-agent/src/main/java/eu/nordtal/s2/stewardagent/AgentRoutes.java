package eu.nordtal.s2.stewardagent;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.stewardagent.docker.Console;
import eu.nordtal.s2.stewardagent.docker.Containers;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerException;
import eu.nordtal.s2.stewardagent.measure.Sampler;
import eu.nordtal.s2.stewardagent.topology.ComposeTopology;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.NotFoundResponse;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The routes that read the daemon or act on one container: the topology, the containers, the host and the console.
 *
 * Every failure leaves as a {@link AgentWire.Refusal}, so steward can show the sentence and where it came from.
 */
final class AgentRoutes {

    private static final Logger log = LoggerFactory.getLogger(AgentRoutes.class);

    private final Docker docker;
    private final Containers containers;
    private final Console console;
    private final Sampler sampler;
    private final ComposeTopology topology;

    AgentRoutes(
            final Docker docker,
            final Containers containers,
            final Console console,
            final Sampler sampler,
            final ComposeTopology topology) {
        this.docker = docker;
        this.containers = containers;
        this.console = console;
        this.sampler = sampler;
        this.topology = topology;
    }

    void register(final JavalinConfig config) {
        config.routes.get(AgentWire.TOPOLOGY, ctx -> ctx.json(topology.read()));
        config.routes.get(AgentWire.CONTAINERS, ctx -> ctx.json(containers.list(sampler.latestByService())));
        config.routes.get(AgentWire.CONTAINER, this::one);
        config.routes.post(AgentWire.STOP, ctx -> ctx.json(containers.stop(ctx.pathParam("id"))));
        config.routes.post(AgentWire.START, ctx -> ctx.json(containers.start(ctx.pathParam("id"))));
        config.routes.get(AgentWire.IMAGES, ctx -> ctx.json(containers.images()));
        config.routes.post(AgentWire.CONSOLE, this::console);
        config.routes.get(AgentWire.HOST, ctx -> ctx.json(host()));
        config.routes.get(AgentWire.SAMPLES, ctx -> ctx.json(sampler.after(after(ctx))));
        refusals(config);
    }

    private void one(final Context ctx) {
        final String service = ctx.pathParam("service");
        ctx.json(containers
                .one(service, sampler.latestByService().get(service))
                .orElseThrow(() -> new NotFoundResponse("no container for " + service)));
    }

    private void console(final Context ctx) {
        final AgentWire.ConsoleLine line = ctx.bodyAsClass(AgentWire.ConsoleLine.class);
        // Gson leaves a missing field null whatever the record declares.
        if (line == null || line.command() == null || line.actor() == null) {
            throw new BadRequestResponse("a console line carries the command and who typed it");
        }
        console.send(ctx.pathParam("service"), line.command().strip(), line.actor());
        ctx.status(202).json(line);
    }

    /** The newest round's host numbers, and what Docker's images and volumes take, asked now. */
    private AgentWire.Host host() {
        final AgentWire.Round round = sampler.latest();
        final AgentWire.HostNumbers numbers = round == null ? null : round.host();
        final String unreadable = numbers != null
                ? null
                : round == null ? "the sampler has not taken its first round yet" : "/proc could not be read";
        try {
            final Docker.DiskUsage usage = docker.diskUsage();
            return new AgentWire.Host(numbers, unreadable, usage.imagesBytes(), usage.volumesBytes(), null);
        } catch (final DockerException e) {
            return new AgentWire.Host(numbers, unreadable, null, null, e.getMessage());
        }
    }

    private static @Nullable Instant after(final Context ctx) {
        final String after = ctx.queryParam("after");
        if (after == null || after.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(after);
        } catch (final DateTimeParseException malformed) {
            throw new BadRequestResponse("after is an ISO instant, not " + after);
        }
    }

    /** Every failure as a sentence: the daemon's as 502, compose's as 502, a refused request as 400. */
    static void refusals(final JavalinConfig config) {
        config.routes.exception(DockerException.class, (failure, ctx) -> {
            log.warn("the Docker daemon did not answer: {}", failure.getMessage());
            ctx.status(502).json(new AgentWire.Refusal(String.valueOf(failure.getMessage()), "docker"));
        });
        config.routes.exception(UncheckedIOException.class, (failure, ctx) -> {
            log.warn("compose could not be asked: {}", failure.getMessage());
            ctx.status(502).json(new AgentWire.Refusal(String.valueOf(failure.getMessage()), "compose"));
        });
        config.routes.exception(
                HttpResponseException.class,
                (failure, ctx) -> ctx.status(failure.getStatus())
                        .json(new AgentWire.Refusal(String.valueOf(failure.getMessage()), AgentWire.SERVICE)));
        config.routes.exception(
                IllegalArgumentException.class,
                (failure, ctx) -> ctx.status(400)
                        .json(new AgentWire.Refusal(String.valueOf(failure.getMessage()), AgentWire.SERVICE)));
    }
}
