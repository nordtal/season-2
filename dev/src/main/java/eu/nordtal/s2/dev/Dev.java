package eu.nordtal.s2.dev;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The local season 2 network: the same compose.yml, Dockerfiles and steward-worker production runs, on local jars.
 *
 * Runs from IntelliJ's Run menu or as {@code sh gradlew -q :dev:run --args="<command>"}, on Windows, macOS and
 * Linux alike; it needs Java and Docker and nothing else.
 */
public final class Dev {

    /** What {@code help} prints; {@code DevTest} holds every command of {@link #run} to a line here. */
    static final String HELP = """
            The local season 2 network, on locally built jars.

              init                 write deploy/dev.env: four secrets generated, the few things only a
                                   person knows asked for in the installer's own words
              up                   build everything, then bring the stack up
              deploy [module...]   rebuild those modules and restart their servers (default: all four)
                                   (a jar swap. After editing deploy/dev.env, run up instead: a restart
                                    reuses the container and its environment with it)
              pack                 build the resource pack and point the proxy at it
              ui                   the interface: the stack in the background, Vite in front
                                   (http://localhost:5173. Stopping it leaves the containers up)
              logs [service]       follow the logs
              console <service>    the server's log, and every line typed sent as a console command
              mc <service> <cmd>   send one console command
              psql                 a psql shell on the local database
              ps | stop | down     the usual
              reset <service>      throw away one server's WORLD volume, after the name is typed back
              help                 this list
            """;

    private Dev() {}

    public static void main(final String[] args) {
        final Terminal terminal = Terminal.system();
        try {
            run(Repository.root(Path.of("")), terminal, Arrays.asList(args));
        } catch (final Processes.Failure failure) {
            System.err.println("\u001b[31m[dev]\u001b[0m " + failure.getMessage());
            System.exit(1);
        }
    }

    /** Runs one command, as named in {@link #HELP}, against the checkout at {@code root}. */
    static void run(final Path root, final Terminal terminal, final List<String> args) {
        final String command = args.isEmpty() ? "help" : args.getFirst();
        final List<String> rest = args.isEmpty() ? List.of() : args.subList(1, args.size());
        final Processes processes = new Processes(root);
        final Compose compose = new Compose(root, processes, terminal);
        final Stack stack = new Stack(root, compose, processes, terminal);
        switch (command) {
            case "init" -> new Setup(root, compose, terminal).init();
            case "up" -> stack.up();
            case "deploy" -> stack.deploy(rest);
            case "pack" -> stack.pack();
            case "ui" -> stack.ui();
            case "logs" -> compose.run(concat(List.of("logs", "-f"), rest));
            case "ps" -> compose.run("ps");
            case "stop" -> compose.run("stop");
            case "down" -> compose.run("down");
            case "console" -> stack.console(one(rest, "usage: dev console <service>"));
            case "mc" -> {
                if (rest.size() < 2) {
                    throw new Processes.Failure("usage: dev mc <service> <command>");
                }
                Processes.require(
                        compose.exec(rest.getFirst(), "mc", String.join(" ", rest.subList(1, rest.size()))),
                        "mc " + rest.getFirst());
            }
            case "psql" -> stack.psql();
            case "reset" -> stack.reset(rest.isEmpty() ? "" : rest.getFirst());
            case "help", "-h", "--help" -> terminal.print(HELP);
            default -> throw new Processes.Failure("unknown command '" + command + "'. Try: dev help");
        }
    }

    private static String one(final List<String> rest, final String usage) {
        if (rest.size() != 1) {
            throw new Processes.Failure(usage);
        }
        return rest.getFirst();
    }

    private static String[] concat(final List<String> first, final List<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toArray(String[]::new);
    }
}
