package eu.nordtal.s2.steward.docker;

import eu.nordtal.s2.steward.plan.Topology;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends one line to a Minecraft server's console; the reply lands in {@code docker logs}, where the interface reads it.
 *
 * Only the four Minecraft servers have a console; every other service is refused by name.
 */
public final class Console {

    private static final Logger log = LoggerFactory.getLogger(Console.class);

    /** The four that have a console. */
    public static final Set<String> WITH_A_CONSOLE =
            Set.of(Topology.PROXY, Topology.LIMBO, Topology.HUNGER_GAMES, Topology.SMP);

    private final Docker docker;
    private final String project;

    public Console(final Docker docker, final String project) {
        this.docker = docker;
        this.project = project;
    }

    public static boolean has(final String service) {
        return WITH_A_CONSOLE.contains(service);
    }

    /**
     * Sends one line to a server's console, as an argument and never through a shell.
     *
     * @param service the compose service name, one of {@link #WITH_A_CONSOLE}
     * @param command the line as typed, without a leading slash
     * @throws IllegalArgumentException if that service has no console, naming what it has instead
     * @throws DockerException if the container is not there or the exec failed
     */
    public void send(final String service, final String command) {
        if (!has(service)) {
            throw new IllegalArgumentException(service + " has no console: " + why(service)
                    + ". The four Minecraft services are " + String.join(", ", WITH_A_CONSOLE) + ".");
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException("an empty line is not a command");
        }
        final String containerId = containerOf(service)
                .orElseThrow(() -> new DockerException(
                        "no running container for " + service + ", so there is no console to type into"));

        // `mc` exits once tmux has the line, so an empty answer is success.
        final Docker.ExecResult answer = docker.exec(containerId, List.of("mc", command));
        if (!answer.ok()) {
            // `mc` failing means the server never saw the line.
            throw new DockerException("`mc " + command + "` in " + service + " exited " + answer.exitCode() + ": "
                    + answer.output().strip());
        }
        if (!answer.output().isBlank()) {
            // Only `mc` itself writes here; the server's reply never does.
            log.info("console {}: {}", service, answer.output().strip());
        }
    }

    private Optional<String> containerOf(final String service) {
        return docker.containers(project).stream()
                .filter(container -> service.equals(container.service()) && container.isRunning())
                .map(Docker.Container::id)
                .findFirst();
    }

    private static String why(final String service) {
        return switch (service) {
            case Topology.DISCORD_BOT -> "its console is Discord";
            case Topology.STEWARD -> "its console is this interface";
            case "postgres" ->
                "a database is not driven by typing into a terminal, and `psql` on "
                        + "the host is the tool for the times when it is";
            // Caddy, not the Velocity proxy, which has a console.
            case "caddy" -> "a reverse proxy has no console; its configuration is a file";
            default -> "it runs no console";
        };
    }
}
