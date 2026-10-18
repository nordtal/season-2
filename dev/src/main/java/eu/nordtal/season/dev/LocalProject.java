package eu.nordtal.season.dev;

import eu.nordtal.season.stewardagent.Compose;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The local compose project. It runs the compose.yml of production with {@code deploy/dev.env} and local jars.
 * Its command lines are those of the {@link Compose} class of steward-agent, run on this terminal.
 * Plugins are local directories.
 */
final class LocalProject {

    static final String ENV_FILE = "deploy/dev.env";

    private final Path root;
    private final EnvFile env;
    private final Processes processes;
    private final Terminal terminal;

    LocalProject(final Path root, final Processes processes, final Terminal terminal) {
        this.root = root;
        this.env = new EnvFile(root.resolve(ENV_FILE));
        this.processes = processes;
        this.terminal = terminal;
    }

    EnvFile env() {
        return env;
    }

    void requireEnv() {
        if (!env.exists()) {
            throw new Processes.Failure(ENV_FILE + " is not there. Run: dev init");
        }
    }

    void run(final String... arguments) {
        Processes.require(processes.run(command(arguments)), "docker compose " + String.join(" ", arguments));
    }

    List<String> command(final String... arguments) {
        requireEnv();
        return new Compose(root.resolve("compose.yml"), root.resolve(ENV_FILE), root, project())
                .command(List.of(arguments));
    }

    /**
     * Runs {@code arguments} inside {@code service}.
     *
     * It allocates a pseudo-terminal only when this program has one. The run console of IntelliJ is a pipe.
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
     * Returns the {@code plugins/} directory of one service on this machine.
     *
     * A value without a {@code /} is a volume name, and this program cannot copy a jar into a volume.
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

    Path secretsDir(final String service) {
        final String value = env.value("NORDTAL_SECRETS_DIR")
                .filter(found -> !found.isBlank())
                .orElseThrow(() -> new Processes.Failure(ENV_FILE + " sets no NORDTAL_SECRETS_DIR. The local stack"
                        + " needs a path there; see deploy/dev.env.example"));
        return root.resolve(value).resolve(service).normalize();
    }

    Path packRoot() {
        return root.resolve(
                        env.value("PACK_ROOT").filter(value -> !value.isBlank()).orElse("./deploy/pack"))
                .normalize();
    }

    String valueOr(final String name, final String fallback) {
        return env.value(name).filter(value -> !value.isBlank()).orElse(fallback);
    }

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
