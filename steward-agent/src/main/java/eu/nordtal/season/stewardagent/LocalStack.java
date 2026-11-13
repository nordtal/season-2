package eu.nordtal.season.stewardagent;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.stewardagent.docker.Containers;
import eu.nordtal.season.stewardagent.docker.Docker;
import eu.nordtal.season.stewardagent.docker.DockerException;
import eu.nordtal.season.stewardagent.run.ContainerOps;
import eu.nordtal.season.stewardagent.topology.ComposeTopology;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops, starts and recreates containers through this process's daemon and compose file.
 *
 * Deploy and recreate wait for compose to finish.
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
        containers.runtime().services().stream()
                .filter(entry -> containerId.equals(entry.containerId()))
                .findFirst()
                .ifPresent(entry -> handOverMounts(entry.service()));
        return containers.start(containerId);
    }

    @Override
    public List<String> lastLines(final String containerId, final int count) {
        try {
            return docker.recentLines(
                    containerId, count, !docker.inspect(containerId).tty());
        } catch (final DockerException unread) {
            log.warn("container log unreadable: container={}", containerId, unread);
            return List.of();
        }
    }

    @Override
    public java.util.Optional<List<String>> missingSecrets(final String containerId, final List<String> names) {
        // The image's own secrets.sh reads the file as the entrypoint does and prints only the names without a value.
        final List<String> command = new ArrayList<>(List.of(
                "sh", "-c", ". /app/secrets.sh && missing_secrets \"$SECRETS_DIRECTORY/secrets.env\" \"$@\"", "sh"));
        command.addAll(names);
        try {
            final Docker.ExecResult answer = docker.exec(containerId, command);
            if (!answer.ok()) {
                log.warn("missing secrets not read: container={} exit_code={}", containerId, answer.exitCode());
                return java.util.Optional.empty();
            }
            // Anything but an asked name is dropped, so no other output reaches the record.
            return java.util.Optional.of(answer.output()
                    .lines()
                    .map(String::strip)
                    .filter(names::contains)
                    .distinct()
                    .toList());
        } catch (final DockerException unasked) {
            log.warn("missing secrets not read: container={} cause={}", containerId, unasked.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * A Minecraft status ping from inside the server's own container, since steward-agent is on none of its networks.
     *
     * Inline rather than a script of the image, because a standby may still run the image of the release before.
     * Velocity behind Caddy expects a PROXY header first; protocol -1 asks for none in particular.
     */
    static final String STATUS_PING = """
            port=${SERVER_PORT:-25565}
            exec 3<>"/dev/tcp/127.0.0.1/$port" || exit 2
            if grep -qs '^[[:space:]]*haproxy-protocol[[:space:]]*=[[:space:]]*true' /data/velocity.toml; then
                printf 'PROXY TCP4 127.0.0.1 127.0.0.1 40000 %s\\r\\n' "$port" >&3
            fi
            printf "\\x13\\x00\\xff\\xff\\xff\\xff\\x0f\\x09localhost\\x$(printf %02x $((port >> 8)))\\x$(printf %02x $((port & 255)))\\x01\\x01\\x00" >&3
            timeout 2 cat <&3 | tr -d '\\000'
            exit 0
            """;

    @Override
    public OptionalInt protocol(final String containerId) {
        try {
            final Docker.ExecResult answer = docker.exec(containerId, List.of("bash", "-c", STATUS_PING));
            if (!answer.ok()) {
                log.warn("status ping unanswered: container={} exit_code={}", containerId, answer.exitCode());
                return OptionalInt.empty();
            }
            return StatusAnswer.protocol(answer.output());
        } catch (final DockerException unasked) {
            log.warn("status ping not sent: container={} cause={}", containerId, unasked.getMessage());
            return OptionalInt.empty();
        }
    }

    @Override
    public void handOverMounts(final String service) {
        try {
            compose.handOver(
                    List.of(service), line -> log.info("hand-over output: service={} line=\"{}\"", service, line));
        } catch (final java.io.IOException | RuntimeException failure) {
            log.warn("volume hand-over failed: service={} cause={}", service, failure.getMessage());
        }
    }

    @Override
    public ImageResult images() {
        return containers.images();
    }

    @Override
    public boolean isOutdated(final String service) {
        return containers.isOutdated(service);
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

    @Override
    public RedeployResult renewAgent() {
        return ran("renewal", Compose.SELF, output -> compose.bootstrap(List.of(Compose.SELF), output));
    }

    @Override
    public Pruned pruneImages() {
        try {
            final Docker.Pruned pruned = docker.prune();
            log.info("unused images and build cache pruned: count={} bytes={}", pruned.images(), pruned.freedBytes());
            return new Pruned(pruned.images(), pruned.removed(), pruned.freedBytes(), pruned.cacheBytes(), null);
        } catch (final DockerException refused) {
            log.warn("unused images not pruned: cause={}", refused.getMessage());
            return new Pruned(0, Map.of(), 0, 0, String.valueOf(refused.getMessage()));
        }
    }

    private static RedeployResult composed(final String what, final String service, final Command command) {
        if (Compose.SELF.equals(service)) {
            return RedeployResult.refused(
                    Compose.SELF + " does not " + what + " itself; a run of a newer release renews it last");
        }
        return ran(what, service, command);
    }

    private static RedeployResult ran(final String what, final String service, final Command command) {
        final List<String> lines = new ArrayList<>();
        final Consumer<String> output = line -> {
            lines.add(line);
            log.info("compose output: action={} service={} line=\"{}\"", what, service, line);
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
