package eu.nordtal.season.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The commands that build, install or drive the running stack. */
final class Stack {

    private static final String MOUNTED_JAR = "/tmp/nordtal-install.jar";

    /**
     * Removes every other jar of the plugin, copies the new jar in and gives it the owner of the folder.
     *
     * Paper splits the name of a jar at its last hyphen, so two versions of one plugin in the folder both load.
     */
    private static final String INSTALL_SCRIPT = "set -e; dir=/data/plugins; name=\"$1\"; module=\"$2\";"
            + " for old in \"$dir/$module\"-*.jar; do"
            + " [ -e \"$old\" ] && [ \"${old##*/}\" != \"$name\" ] || continue;"
            + " rm -f \"$old\"; echo \"removed superseded ${old##*/}\"; done;"
            + " cp " + MOUNTED_JAR + " \"$dir/$name\"; chown --reference=\"$dir\" \"$dir/$name\"";

    private final Path root;
    private final LocalProject compose;
    private final Processes processes;
    private final Terminal terminal;

    Stack(final Path root, final LocalProject compose, final Processes processes, final Terminal terminal) {
        this.root = root;
        this.compose = compose;
        this.processes = processes;
        this.terminal = terminal;
    }

    void up() {
        compose.requireEnv();
        build(ResetGuard.SERVICES);
        processes.gradle(":imageContexts");
        // The images are built and never pulled, and before the plugins, which the Minecraft image installs.
        terminal.log("images building");
        compose.run("build");
        ResetGuard.SERVICES.forEach(this::install);
        compose.run("up", "-d");
        terminal.log("stack started");
        terminal.log("The first start takes a few minutes. Follow it with: dev logs");
    }

    void deploy(final List<String> modules) {
        final List<String> chosen = modules.isEmpty() ? ResetGuard.SERVICES : modules;
        for (final String module : chosen) {
            if (!ResetGuard.known(module)) {
                throw new Processes.Failure(
                        "Unknown module " + module + ". Name one of: " + String.join(" ", ResetGuard.SERVICES));
            }
        }
        compose.requireEnv();
        build(chosen);
        chosen.forEach(this::install);
        // restart keeps the old environment of the container. `up` applies an edit of the env file.
        terminal.log("services restarting: service=\"" + String.join(" ", chosen) + "\"");
        final List<String> restart = new ArrayList<>(List.of("restart"));
        restart.addAll(chosen);
        compose.run(restart.toArray(String[]::new));
    }

    /**
     * Starts the interface with hot reload: the stack in the background and Vite in front on :5173.
     * The command names the steward services, so Caddy does not start.
     * Stopping it leaves the containers running.
     */
    void ui() {
        compose.requireEnv();
        final String stewardPort = compose.valueOr("STEWARD_PORT", "8080");
        terminal.log("stack starting: profiles=\"" + compose.valueOr("COMPOSE_PROFILES", "") + "\"");
        compose.run("up", "-d");
        terminal.log("interface services starting: service=\"steward steward-agent\"");
        compose.run("up", "-d", "steward", "steward-agent");
        terminal.log("interface starting: url=http://localhost:5173 container_url=http://127.0.0.1:" + stewardPort);
        terminal.log("Stopping this leaves the containers running. Stop them with: dev stop");
        processes.gradle(":steward:viteDev");
    }

    /**
     * Opens a console that prints the log of the server and sends every typed line as a command.
     * A real terminal can attach with {@code docker compose exec <service> console}.
     * The run console of IntelliJ cannot.
     */
    void console(final String service) {
        final Process logs = processes.start(compose.command("logs", "-f", "--tail", "30", service), false);
        terminal.log("Each line you type goes to the console of " + service + ". Ctrl-D leaves.");
        try {
            for (Optional<String> line = terminal.readLine(); line.isPresent(); line = terminal.readLine()) {
                final String command = line.get().strip();
                if (!command.isEmpty()) {
                    compose.exec(service, "mc", command);
                }
            }
        } finally {
            logs.destroy();
        }
    }

    void psql() {
        compose.exec("postgres", "sh", "-c", "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"");
    }

    void reset(final String service) {
        if (!ResetGuard.known(service)) {
            throw new Processes.Failure("Name the service to reset: dev reset <service>, one of: "
                    + String.join(" ", ResetGuard.SERVICES) + ".");
        }
        terminal.print("This deletes the " + service + " container AND its volume: world, .server cache, logs.\n"
                + "plugins/ is a directory on the host and survives.\nType the service name to confirm: ");
        if (!ResetGuard.confirmed(service, terminal.readLine().orElse(""))) {
            throw new Processes.Failure("Reset cancelled: the name did not match. Nothing was deleted.");
        }
        // `compose rm -v` removes only anonymous volumes, so the command removes the world volume by name.
        final List<String> volumes =
                new ArrayList<>(worldVolume(service).stream().toList());
        compose.run("rm", "-sf", service);
        if (volumes.isEmpty()) {
            volumes.addAll(lines(processes.output(List.of(
                    "docker",
                    "volume",
                    "ls",
                    "-q",
                    "--filter",
                    "label=com.docker.compose.project=" + compose.project(),
                    "--filter",
                    "label=com.docker.compose.volume=mc-" + service))));
        }
        if (volumes.isEmpty()) {
            terminal.log("container removed, no volume found: service=" + service + " volume=mc-" + service);
        } else if (volumes.size() > 1) {
            throw new Processes.Failure("Reset stopped: more than one mc-" + service + " volume exists. Delete the one"
                    + " of this project with docker volume rm: " + String.join(" ", volumes));
        } else {
            Processes.require(
                    processes.run(List.of("docker", "volume", "rm", volumes.getFirst())),
                    "docker volume rm " + volumes.getFirst());
            terminal.log("volume removed: volume=" + volumes.getFirst());
        }
        terminal.log(service + " is reset. Run dev up to start it on an empty volume.");
    }

    private Optional<String> worldVolume(final String service) {
        final List<String> containers = lines(processes.output(compose.command("ps", "-aq", service)));
        if (containers.isEmpty()) {
            return Optional.empty();
        }
        final String volume = processes.output(List.of(
                "docker",
                "inspect",
                "-f",
                "{{range .Mounts}}{{if eq .Destination \"/data\"}}{{.Name}}{{end}}{{end}}",
                containers.getFirst()));
        return volume.isBlank() ? Optional.empty() : Optional.of(volume);
    }

    private void build(final List<String> modules) {
        terminal.log("modules building: module=\"" + String.join(" ", modules) + "\"");
        processes.gradle(
                modules.stream().map(module -> ":" + module + ":shadowJar").toArray(String[]::new));
    }

    /**
     * Copies the jar of a module into the plugins/ folder of its server, as root in a temporary container.
     *
     * After the first start, the folder belongs to the uid of the server. See {@link #INSTALL_SCRIPT}.
     */
    private void install(final String module) {
        final Path jar = Repository.jar(root, module);
        if (!Files.isRegularFile(jar)) {
            throw new Processes.Failure(jar + " is missing after the build. Run sh gradlew :" + module
                    + ":shadowJar and check its output.");
        }
        final Path target = compose.pluginsDir(module);
        try {
            // Docker would create a missing folder as root.
            Files.createDirectories(target);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot create " + target, e);
        }
        compose.run(installArguments(module, jar.toAbsolutePath()).toArray(String[]::new));
        terminal.log("jar installed: file=" + jar.getFileName() + " dir=" + target);
    }

    /**
     * Returns the compose arguments that install {@code jar} into the plugins/ folder of {@code module}.
     *
     * Root keeps only the capabilities that the copy needs: writing into a foreign folder and changing the owner.
     */
    static List<String> installArguments(final String module, final Path jar) {
        return List.of(
                "run",
                "--rm",
                "--no-deps",
                "-T",
                "--pull",
                "never",
                "--user",
                "0:0",
                "--cap-add",
                "DAC_OVERRIDE",
                "--cap-add",
                "CHOWN",
                "--volume",
                jar + ":" + MOUNTED_JAR + ":ro",
                "--entrypoint",
                "sh",
                module,
                "-c",
                INSTALL_SCRIPT,
                "sh",
                jar.getFileName().toString(),
                module);
    }

    private static List<String> lines(final String output) {
        return output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }
}
