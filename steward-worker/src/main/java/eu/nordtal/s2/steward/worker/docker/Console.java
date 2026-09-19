package eu.nordtal.s2.steward.worker.docker;

import eu.nordtal.s2.steward.worker.plan.Topology;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The console of §10a.2: one line, typed into one server.
 *
 * <h2>The answer does not come back here, and that is not a limitation</h2>
 * {@code mc} inside the Minecraft image hands the line to the tmux session the server runs in and
 * returns immediately - the server's reply is printed on its console and therefore lands in
 * {@code docker logs}, where the interface is already watching. So a command and its answer arrive
 * on the same stream and in the same order as everybody else's, which is what makes a second
 * admin's line visible to the first. A console that collected its own replies privately would make
 * two people typing look like one person hallucinating.
 *
 * <h2>Four services have one, six do not</h2>
 * The four Minecraft servers run a console because they have one. {@code postgres} and
 * {@code caddy} have no such thing; {@code discord-bot}'s interface is Discord; steward-worker's
 * own is this. The rule lives here rather than in {@link Docker} deliberately: a general-purpose
 * exec that quietly refuses some containers is a puzzle, while a named boundary is a boundary. It
 * is also why the interface shows no console field at all for those six, rather than a disabled
 * one (§10c).
 */
public final class Console {

    private static final Logger log = LoggerFactory.getLogger(Console.class);

    /** The four that have a console. Everything else is refused by name. */
    public static final Set<String> WITH_A_CONSOLE = Set.of(
            Topology.PROXY, Topology.LIMBO, Topology.HUNGER_GAMES, Topology.SMP);

    private final Docker docker;
    private final String project;

    public Console(final @NotNull Docker docker, final @NotNull String project) {
        this.docker = docker;
        this.project = project;
    }

    public static boolean has(final @NotNull String service) {
        return WITH_A_CONSOLE.contains(service);
    }

    /**
     * Sends one line to a server's console.
     *
     * <p>The command is passed as an argument and never through a shell: no quoting, no
     * interpretation, no way for a semicolon in a message to become a second command.</p>
     *
     * @param service the compose service name, which must be one of {@link #WITH_A_CONSOLE}
     * @param command the line as typed, without a leading slash - {@code list}, {@code say hello}
     * @throws IllegalArgumentException if that service has no console, naming what it has instead
     * @throws DockerException          if the container is not there or the exec failed
     */
    public void send(final @NotNull String service, final @NotNull String command) {
        if (!has(service)) {
            throw new IllegalArgumentException(service + " has no console: " + why(service)
                    + ". The four Minecraft services are " + String.join(", ", WITH_A_CONSOLE) + ".");
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException("an empty line is not a command");
        }
        final String containerId = containerOf(service).orElseThrow(() -> new DockerException(
                "no running container for " + service + ", so there is no console to type into"));

        // `mc` is in the image and is the supported way in - see deploy/minecraft/Dockerfile. It
        // exits as soon as tmux has the line, so an empty answer here is success, not silence.
        final Docker.ExecResult answer = docker.exec(containerId, List.of("mc", command));
        if (!answer.ok()) {
            // `mc` failing is not the server refusing the command - the server never sees a line
            // that `mc` could not hand to tmux. Saying "sent" here would be a lie with a
            // convincing shape.
            throw new DockerException("`mc " + command + "` in " + service + " exited "
                    + answer.exitCode() + ": " + answer.output().strip());
        }
        if (!answer.output().isBlank()) {
            // Only `mc` itself talks here, and only when it is unhappy about something it survived.
            // The server's own reply never comes this way.
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
            case Topology.STEWARD_WORKER -> "its console is this interface";
            case "postgres" -> "a database is not driven by typing into a terminal, and `psql` on "
                    + "the host is the tool for the times when it is";
            // caddy, and it says "reverse proxy" rather than naming the service because that is
            // what it is. NOT the Velocity proxy: since season-2-ops/117 the service called
            // `proxy` is a Minecraft server and is in WITH_A_CONSOLE, so this branch is
            // unreachable for it - which is exactly why it had to stop saying "proxy".
            case "caddy" -> "a reverse proxy has no console; its configuration is a file";
            default -> "it runs no console";
        };
    }
}
