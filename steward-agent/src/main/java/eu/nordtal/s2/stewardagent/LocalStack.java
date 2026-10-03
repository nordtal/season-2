package eu.nordtal.s2.stewardagent;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import eu.nordtal.s2.internalapi.agent.RedeployResult;
import eu.nordtal.s2.internalapi.agent.RuntimeResult;
import eu.nordtal.s2.stewardagent.docker.Containers;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.run.ContainerOps;
import eu.nordtal.s2.stewardagent.topology.ComposeTopology;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a run stops, starts and makes again through: this process's own daemon and compose file.
 *
 * Deploy and recreate block until compose is finished, since the run waits on them anyway.
 */
final class LocalStack implements ContainerOps {

    private static final Logger log = LoggerFactory.getLogger(LocalStack.class);

    private final Docker docker;
    private final Containers containers;
    private final ComposeTopology topology;
    private final Compose compose;

    LocalStack(
            final Docker docker, final Containers containers, final ComposeTopology topology, final Compose compose) {
        this.docker = docker;
        this.containers = containers;
        this.topology = topology;
        this.compose = compose;
    }

    @Override
    public AgentWire.Topology topology() {
        return topology.read();
    }

    @Override
    public RuntimeResult runtime() {
        return containers.runtime();
    }

    @Override
    public RedeployResult stop(final String containerId) {
        return containers.stop(containerId);
    }

    @Override
    public RedeployResult start(final String containerId) {
        return containers.start(containerId);
    }

    @Override
    public ImageResult images() {
        return containers.images();
    }

    @Override
    public RedeployResult deploy(final String service) {
        return composed(
                "deploy",
                service,
                output -> StewardAgent.deploy(compose, List.of(service), output, false, docker::hasImage));
    }

    @Override
    public RedeployResult recreate(final String service) {
        return composed(
                "recreate", service, output -> StewardAgent.recreate(compose, service, output, docker::hasImage));
    }

    @Override
    public RedeployResult standby(final String service) {
        return composed("standby", service, output -> StewardAgent.standby(compose, service, output, docker::hasImage));
    }

    @Override
    public RedeployResult migrate() {
        return ran("migration", Compose.MIGRATE, compose::migrate);
    }

    @Override
    public String oneShot() {
        return compose.oneShotName();
    }

    /**
     * Pulls steward-agent at {@code release}, copies its compose file out of the image and runs the one-shot from it.
     *
     * So the one-shot is made exactly as that release defines steward-agent.
     */
    @Override
    public RedeployResult handOver(final long id, final String release) {
        return ran("hand-over", Compose.SELF, output -> {
            final Compose current = compose.atRelease(release);
            if (current.pull(Compose.SELF, output, docker::hasImage) == Compose.PullOutcome.FAILED) {
                output.accept("no steward-agent image at " + release + ", from the registry or from this host");
                return 1;
            }
            final String image = current.imageOf(Compose.SELF)
                    .orElseThrow(() -> new IllegalStateException("compose.yml names no image for " + Compose.SELF));
            final Path file =
                    Files.createTempDirectory("release-" + release + "-").resolve("compose.yml");
            docker.copyOutOfImage(image, Compose.IN_IMAGE, file);
            return compose.atRelease(release, file).runOneShot(id, output);
        });
    }

    /** The one-shot's last step: compose makes the long-running steward-agent again, which is not this container. */
    @Override
    public RedeployResult renewAgent() {
        return ran("renewal", Compose.SELF, output -> compose.bootstrap(List.of(Compose.SELF), output));
    }

    /** One compose command for one service, its output logged and its last line the answer when it fails. */
    private static RedeployResult composed(final String what, final String service, final Command command) {
        if (Compose.SELF.equals(service)) {
            return RedeployResult.refused(
                    Compose.SELF + " does not " + what + " itself; a run of a newer release renews it last");
        }
        return ran(what, service, command);
    }

    /** {@link #composed}, for the commands that may name steward-agent. */
    private static RedeployResult ran(final String what, final String service, final Command command) {
        final List<String> lines = new ArrayList<>();
        final Consumer<String> output = line -> {
            lines.add(line);
            log.info("[{} {}] {}", what, service, line);
        };
        try {
            final int status = command.run(output);
            if (status == 0) {
                return RedeployResult.triggered("finished the " + what + " of " + service);
            }
            return RedeployResult.refused("the " + what + " of " + service + " failed: "
                    + (lines.isEmpty() ? "compose said nothing" : lines.getLast()));
        } catch (final Exception failure) {
            return RedeployResult.unverified("the " + what + " of " + service + " broke off: " + failure.getMessage());
        }
    }

    @FunctionalInterface
    private interface Command {
        int run(Consumer<String> output) throws Exception;
    }
}
