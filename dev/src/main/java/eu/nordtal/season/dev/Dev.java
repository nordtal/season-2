package eu.nordtal.season.dev;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The local season 2 network. It runs the compose.yml, the Dockerfiles and the steward of production with local jars.
 *
 * Run it as {@code sh gradlew -q :dev:run --args="<command>"} on any operating system with Java and Docker.
 */
public final class Dev {

    /** The text that {@code help} prints. {@code DevTest} checks that every command of {@link #run} has a line here. */
    static final String HELP = """
            Run the local season 2 network from source.
            Plugin modules: proxy, limbo, hunger-games, smp. Run ps to see service names.

              init                 create deploy/dev.env, generate secrets and ask for missing settings
              up                   build images and start the stack
              deploy [module...]   rebuild plugin modules and restart their services (default: all four)
                                   Run up after editing deploy/dev.env to apply the new environment.
              ui                   start Steward and Vite at http://localhost:5173
                                   Containers keep running when Vite stops.
              logs [service]       follow service logs
              console <service>    read logs and send console commands
              mc <service> <cmd>   send one Minecraft console command
              psql                 open a PostgreSQL shell
              ps                   list containers
              stop                 stop services
              down                 remove stack containers and networks
              reset <service>      delete the service container and world volume after name confirmation
              help                 show commands
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

    static void run(final Path root, final Terminal terminal, final List<String> args) {
        final String command = args.isEmpty() ? "help" : args.getFirst();
        final List<String> rest = args.isEmpty() ? List.of() : args.subList(1, args.size());
        final Processes processes = new Processes(root);
        final LocalProject compose = new LocalProject(root, processes, terminal);
        final Stack stack = new Stack(root, compose, processes, terminal);
        switch (command) {
            case "init" -> new Setup(root, compose, terminal).init();
            case "up" -> stack.up();
            case "deploy" -> stack.deploy(rest);
            case "ui" -> stack.ui();
            case "logs" -> compose.run(concat(List.of("logs", "-f"), rest));
            case "ps" -> compose.run("ps");
            case "stop" -> compose.run("stop");
            case "down" -> compose.run("down");
            case "console" -> stack.console(one(rest, "Name one service: dev console <service>"));
            case "mc" -> {
                if (rest.size() < 2) {
                    throw new Processes.Failure("Name a service and a command: dev mc <service> <command>");
                }
                Processes.require(
                        compose.exec(rest.getFirst(), "mc", String.join(" ", rest.subList(1, rest.size()))),
                        "mc " + rest.getFirst());
            }
            case "psql" -> stack.psql();
            case "reset" -> stack.reset(rest.isEmpty() ? "" : rest.getFirst());
            case "help", "-h", "--help" -> terminal.print(HELP);
            default -> throw new Processes.Failure("Unknown command " + command + ". Run dev help for the list.");
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
