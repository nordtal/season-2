package eu.nordtal.season.stewardagent.docker;

import eu.nordtal.season.common.id.Actor;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends one line to the console of a server. The reply lands in {@code docker logs}.
 *
 * Only a service compose.yml marks as having a console is typed into; every other one is refused by name.
 */
public final class Console {

    private static final Logger log = LoggerFactory.getLogger(Console.class);

    private final Docker docker;
    private final String project;
    private final Supplier<Set<String>> withConsole;

    public Console(final Docker docker, final String project, final Supplier<Set<String>> withConsole) {
        this.docker = docker;
        this.project = project;
        this.withConsole = withConsole;
    }

    /**
     * Sends one line to the console as an argument, never through a shell, and logs who typed it.
     *
     * @param command the line as typed, without a leading slash
     * @param actor who typed it, logged as kind and Discord id
     * @throws IllegalArgumentException if the service has no console, naming those that have one
     * @throws DockerException if the container is not there or the exec failed
     */
    public void send(final String service, final String command, final Actor actor) {
        final Set<String> consoles = withConsole.get();
        if (!consoles.contains(service)) {
            throw new IllegalArgumentException(service + " has no console. The services with one are "
                    + String.join(", ", consoles.stream().sorted().toList()) + ".");
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException("an empty line is not a command");
        }
        final String containerId = docker.running(project, service)
                .orElseThrow(() -> new DockerException(
                        "no running container for " + service + ", so there is no console to type into"));

        log.info("console {} <- {} {}: {}", service, actor.kind(), Objects.requireNonNullElse(actor.id(), ""), command);
        // `mc` exits once tmux has the line, so an empty answer is success.
        final Docker.ExecResult answer = docker.exec(containerId, List.of("mc", command));
        if (!answer.ok()) {
            // `mc` failing means the server never saw the line.
            throw new DockerException("`mc " + command + "` in " + service + " exited " + answer.exitCode() + ": "
                    + answer.output().strip());
        }
        if (!answer.output().isBlank()) {
            // Only `mc` writes here, never the server.
            log.info("console {}: {}", service, answer.output().strip());
        }
    }
}
