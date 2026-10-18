package eu.nordtal.season.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
        terminal.log("building every image of ours the selected profiles use");
        compose.run("build");
        ResetGuard.SERVICES.forEach(this::install);
        compose.run("up", "-d");
        terminal.log("up. First start downloads Paper, Velocity and the third-party plugins; give it a few");
        terminal.log("minutes and watch with: dev logs");
    }

    void deploy(final List<String> modules) {
        final List<String> chosen = modules.isEmpty() ? ResetGuard.SERVICES : modules;
        for (final String module : chosen) {
            if (!ResetGuard.known(module)) {
                throw new Processes.Failure(
                        "unknown module '" + module + "'. One of: " + String.join(" ", ResetGuard.SERVICES));
            }
        }
        compose.requireEnv();
        build(chosen);
        chosen.forEach(this::install);
        // restart keeps the old environment of the container. `up` applies an edit of the env file.
        terminal.log("restarting: " + String.join(" ", chosen));
        final List<String> restart = new ArrayList<>(List.of("restart"));
        restart.addAll(chosen);
        compose.run(restart.toArray(String[]::new));
    }

    /** Builds the resource pack, copies it to the directory that pack-host serves and points the proxy at it. */
    void pack() {
        compose.requireEnv();
        processes.gradle(":resource-pack:packZip");
        final String name = "nordtal-resource-pack-" + Repository.version(root) + ".zip";
        final Path zip = root.resolve("resource-pack/build/distributions").resolve(name);
        if (!Files.isRegularFile(zip)) {
            throw new Processes.Failure(zip + " was not built");
        }
        final String sha1 = sha1(zip);
        final String url = "http://localhost:" + compose.valueOr("PACK_PORT", "8080") + "/" + name;
        try {
            Files.createDirectories(compose.packRoot());
            Files.copy(zip, compose.packRoot().resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot install the pack", e);
        }
        if (compose.exec(
                        "postgres",
                        "sh",
                        "-c",
                        "psql -q -v ON_ERROR_STOP=1 -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -c \"$0\"",
                        storePack(url, sha1))
                != 0) {
            throw new Processes.Failure(
                    "the database refused the pack - has steward started once, so the" + " tables exist?");
        }
        terminal.log("the proxy now sends " + url + " (" + sha1 + ")");
        compose.run("restart", "proxy");
    }

    static String storePack(final String url, final String sha1) {
        return "INSERT INTO setting_override (service, name, path, value, actor_kind, changed) VALUES"
                + " ('proxy', 'pack', 'url', to_jsonb(" + literal(url) + "::text), 'HOST', now()),"
                + " ('proxy', 'pack', 'sha1', to_jsonb(" + literal(sha1) + "::text), 'HOST', now())"
                + " ON CONFLICT (service, name, path) DO UPDATE SET value = excluded.value,"
                + " actor_kind = excluded.actor_kind, actor_id = NULL, changed = excluded.changed";
    }

    private static String literal(final String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * Starts the interface with hot reload: the stack in the background and Vite in front on :5173.
     * The command names the steward services, so Caddy does not start.
     * Stopping it leaves the containers running.
     */
    void ui() {
        compose.requireEnv();
        final String packPort = compose.valueOr("PACK_PORT", "8080");
        final String stewardPort = compose.valueOr("STEWARD_PORT", "8080");
        if (packPort.equals(stewardPort)) {
            throw new Processes.Failure("PACK_PORT and STEWARD_PORT are both " + stewardPort + " in "
                    + LocalProject.ENV_FILE + ", and the resource pack host and the interface cannot both have it. Set"
                    + " PACK_PORT=8081 - the pack URL is written by 'dev pack', which reads that value, so nothing"
                    + " else has to change.");
        }
        terminal.log("bringing up the stack (" + compose.valueOr("COMPOSE_PROFILES", "") + ")");
        compose.run("up", "-d");
        terminal.log("and the two services the interface is");
        compose.run("up", "-d", "steward", "steward-agent");
        terminal.log("the interface is on http://localhost:5173 - the container itself is on 127.0.0.1:" + stewardPort);
        terminal.log("Stopping this stops Vite and LEAVES THE CONTAINERS RUNNING. The counter-command is: dev stop");
        processes.gradle(":steward:viteDev");
    }

    /**
     * Opens a console that prints the log of the server and sends every typed line as a command.
     * A real terminal can attach with {@code docker compose exec <service> console}.
     * The run console of IntelliJ cannot.
     */
    void console(final String service) {
        final Process logs = processes.start(compose.command("logs", "-f", "--tail", "30", service), false);
        terminal.log("typing a line sends it to " + service + "'s console; end the input (Ctrl-D) to leave");
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
            throw new Processes.Failure("usage: dev reset <service>, one of: " + String.join(" ", ResetGuard.SERVICES)
                    + ". A service has to be named: this deletes that server's volume, which on smp is Nordtal - a"
                    + " hand-built world that is in no repository and in no release.");
        }
        terminal.print("This deletes the " + service + " container AND its volume: world, .server cache, logs.\n"
                + "plugins/ is a directory on the host and survives.\nType the service name to confirm: ");
        if (!ResetGuard.confirmed(service, terminal.readLine().orElse(""))) {
            throw new Processes.Failure("not confirmed; nothing was deleted");
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
            terminal.log("no mc-" + service + " volume was there; the container is gone");
        } else if (volumes.size() > 1) {
            throw new Processes.Failure("more than one mc-" + service + " volume exists on this machine, so this"
                    + " cannot tell which one belongs to this project. Delete it by name with docker volume rm: "
                    + String.join(" ", volumes));
        } else {
            Processes.require(
                    processes.run(List.of("docker", "volume", "rm", volumes.getFirst())),
                    "docker volume rm " + volumes.getFirst());
            terminal.log("removed volume " + volumes.getFirst());
        }
        terminal.log(service + " is reset. The next 'dev up' recreates it from an empty volume.");
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
        terminal.log("building: " + String.join(" ", modules));
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
            throw new Processes.Failure(jar + " was not built");
        }
        final Path target = compose.pluginsDir(module);
        try {
            // Docker would create a missing folder as root.
            Files.createDirectories(target);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot create " + target, e);
        }
        compose.run(installArguments(module, jar.toAbsolutePath()).toArray(String[]::new));
        terminal.log("installed " + jar.getFileName() + " into " + target);
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

    private static String sha1(final Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-1", e);
        }
    }
}
