package eu.nordtal.season.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Every command that builds, installs or drives the running stack. */
final class Stack {

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

    /** Builds everything, installs the four plugins, builds every image of ours and brings the stack up. */
    void up() {
        compose.requireEnv();
        build(ResetGuard.SERVICES);
        processes.gradle(":imageContexts");
        ResetGuard.SERVICES.forEach(this::install);
        // Built, never pulled: every image of ours is nordtal/<name>:dev, which no registry has.
        terminal.log("building every image of ours the selected profiles use");
        compose.run("build");
        compose.run("up", "-d");
        terminal.log("up. First start downloads Paper, Velocity and the third-party plugins; give it a few");
        terminal.log("minutes and watch with: dev logs");
    }

    /** Rebuilds {@code modules}, all four when none is named, and restarts their servers. */
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
        // restart keeps the container's old environment; `up` picks up an env edit.
        terminal.log("restarting: " + String.join(" ", chosen));
        final List<String> restart = new ArrayList<>(List.of("restart"));
        restart.addAll(chosen);
        compose.run(restart.toArray(String[]::new));
    }

    /** Builds the resource pack, puts it where pack-host serves it and sets the proxy's pack to it. */
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
        // The SQL travels as $0, so no shell ever reads it.
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

    /** @return the statement that sets the proxy's {@code pack} group to {@code url} and {@code sha1}, as the host */
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
     * The interface with hot reload: the stack in the background, Vite in front on :5173.
     *
     * Naming the steward services keeps Caddy out; stopping this leaves the containers running.
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
     * A console that works in any window: the server's log scrolls by, and every line typed is sent as a command.
     *
     * A real terminal can attach with {@code docker compose exec <service> console}; IntelliJ's run console cannot.
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

    /** A psql shell on the local database; line by line when there is no real terminal. */
    void psql() {
        compose.exec("postgres", "sh", "-c", "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"");
    }

    /** Throws away one server's container and world volume, after the name is typed back. */
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
        // `compose rm -v` removes only anonymous volumes, so the world is found and removed by name.
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

    /** The volume mounted at /data in the service's container, read off the container while it still exists. */
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
     * Copies a module's jar into its server's plugins/, removing every other jar of that plugin first.
     *
     * Paper splits a jar's name on its last hyphen, so two versions of one plugin side by side both load.
     */
    private void install(final String module) {
        final Path jar = Repository.jar(root, module);
        if (!Files.isRegularFile(jar)) {
            throw new Processes.Failure(jar + " was not built");
        }
        final Path target = compose.pluginsDir(module);
        final Pattern sibling = Pattern.compile(Pattern.quote(module) + "-.+\\.jar");
        try {
            Files.createDirectories(target);
            try (DirectoryStream<Path> existing = Files.newDirectoryStream(target, "*.jar")) {
                for (final Path old : existing) {
                    final String name = old.getFileName().toString();
                    if (sibling.matcher(name).matches()
                            && !name.equals(jar.getFileName().toString())) {
                        Files.delete(old);
                        terminal.log("removed superseded " + name);
                    }
                }
            }
            Files.copy(jar, target.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot install " + jar, e);
        }
        terminal.log("installed " + jar.getFileName() + " into " + target);
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
