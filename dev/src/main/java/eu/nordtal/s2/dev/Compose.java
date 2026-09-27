package eu.nordtal.s2.dev;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code docker compose} against {@code deploy/dev.env}: the same compose.yml production runs, pointed at local jars.
 *
 * Each server's {@code plugins/} is a local directory named by {@code <SERVICE>_PLUGINS}, not a volume.
 */
final class Compose {

    static final String ENV_FILE = "deploy/dev.env";

    private final Path root;
    private final EnvFile env;
    private final Processes processes;
    private final Terminal terminal;

    Compose(final Path root, final Processes processes, final Terminal terminal) {
        this.root = root;
        this.env = new EnvFile(root.resolve(ENV_FILE));
        this.processes = processes;
        this.terminal = terminal;
    }

    EnvFile env() {
        return env;
    }

    /** Stops here with the one sentence that helps when {@code deploy/dev.env} is missing. */
    void requireEnv() {
        if (!env.exists()) {
            throw new Processes.Failure(ENV_FILE + " is not there. Run: dev init");
        }
    }

    /** Runs {@code docker compose --env-file deploy/dev.env} with {@code arguments}; a failure ends this program. */
    void run(final String... arguments) {
        Processes.require(processes.run(command(arguments)), "docker compose " + String.join(" ", arguments));
    }

    /** @return the full command line for {@code arguments} */
    List<String> command(final String... arguments) {
        requireEnv();
        final List<String> command = new ArrayList<>(List.of("docker", "compose", "--env-file", ENV_FILE));
        command.addAll(List.of(arguments));
        return command;
    }

    /**
     * Runs {@code arguments} inside {@code service}.
     *
     * Allocates a pseudo-terminal only when this program has one; IntelliJ's run console is a pipe.
     */
    int exec(final String service, final String... arguments) {
        final List<String> command = new ArrayList<>(List.of("exec"));
        if (!terminal.isTerminal()) {
            command.add("-T");
        }
        command.add(service);
        command.addAll(List.of(arguments));
        return processes.run(command(command.toArray(String[]::new)));
    }

    /**
     * Where one service's {@code plugins/} is on this machine.
     *
     * A value without a {@code /} is a volume name, which this program cannot copy a jar into.
     */
    Path pluginsDir(final String service) {
        final String variable = service.toUpperCase(Locale.ROOT).replace('-', '_') + "_PLUGINS";
        final String value = env.value(variable)
                .filter(found -> !found.isBlank())
                .orElseThrow(() -> new Processes.Failure(ENV_FILE + " sets no " + variable
                        + ". The local stack needs a path there; see " + "deploy/dev.env.example"));
        if (!value.contains("/") && !value.contains("\\")) {
            throw new Processes.Failure(variable + " is '" + value + "', which Docker reads as a VOLUME NAME, not a"
                    + " path. The local stack cannot copy jars or read configs out of a volume - set it to a"
                    + " directory.");
        }
        return root.resolve(value).normalize();
    }

    /** The directory pack-host serves; compose.yml defaults it to {@code deploy/pack} too. */
    Path packRoot() {
        return root.resolve(
                        env.value("PACK_ROOT").filter(value -> !value.isBlank()).orElse("./deploy/pack"))
                .normalize();
    }

    /** @return {@code name}'s value in {@code deploy/dev.env}, or {@code fallback} when it is unset or blank */
    String valueOr(final String name, final String fallback) {
        return env.value(name).filter(value -> !value.isBlank()).orElse(fallback);
    }

    /** What Docker labels this project's volumes with: the env file's name, compose.yml's, or the directory's. */
    String project() {
        return env.value("COMPOSE_PROJECT_NAME")
                .filter(value -> !value.isBlank())
                .orElseGet(() -> Repository.composeName(root)
                        .orElse(root.getFileName().toString().toLowerCase(Locale.ROOT)));
    }

    void log(final String message) {
        terminal.log(message);
    }
}
