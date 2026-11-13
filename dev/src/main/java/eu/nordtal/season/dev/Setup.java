package eu.nordtal.season.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The {@code init} command. It writes {@code deploy/dev.env}, generates four secrets and asks for the rest.
 *
 * The example supplies every other value, and the command asks only the {@link LocalQuestions}.
 */
final class Setup {

    private static final String EXAMPLE = "deploy/dev.env.example";

    private static final List<Secret> SECRETS = List.of(
            new Secret("POSTGRES_PASSWORD", 24),
            new Secret("POSTGRES_DISCORD_BOT_PASSWORD", 24),
            new Secret("POSTGRES_PROXY_PASSWORD", 24),
            new Secret("POSTGRES_LIMBO_PASSWORD", 24),
            new Secret("POSTGRES_HUNGER_GAMES_PASSWORD", 24),
            new Secret("POSTGRES_SMP_PASSWORD", 24),
            new Secret("POSTGRES_STEWARD_PASSWORD", 24),
            new Secret("VELOCITY_FORWARDING_SECRET", 24),
            new Secret("STEWARD_AGENT_TOKEN", 32),
            new Secret("STEWARD_BUNQ_TOKEN", 32));

    private record Secret(String name, int bytes) {}

    static final List<String> SECRET_SERVICES = List.of("steward-bunq", "discord-bot", "steward", "steward-agent");

    private final Path root;
    private final LocalProject compose;
    private final Terminal terminal;
    private final SecureRandom random = new SecureRandom();

    Setup(final Path root, final LocalProject compose, final Terminal terminal) {
        this.root = root;
        this.compose = compose;
        this.terminal = terminal;
    }

    void init() {
        final EnvFile env = compose.env();
        if (env.exists()) {
            terminal.log("env file exists, not touched: file=" + LocalProject.ENV_FILE);
        } else {
            copyExample(env.path());
            SECRETS.forEach(secret -> generate(env, secret));
            LocalQuestions.ALL.forEach(question -> ask(env, question));
            deriveRelyingParty(env);
            final List<Integer> leftovers = env.replaceMeLines();
            if (!leftovers.isEmpty()) {
                terminal.warn("REPLACE_ME left without a value: file=" + LocalProject.ENV_FILE + " lines=\"" + leftovers
                        + "\"");
            }
        }
        for (final String service : ResetGuard.SERVICES) {
            createDirectories(compose.pluginsDir(service));
        }
        createDirectories(compose.packRoot());
        // Created here, because Docker would create them as root, and a developer could not write a secret there.
        for (final String service : SECRET_SERVICES) {
            createDirectories(compose.secretsDir(service));
        }
        terminal.log(
                "plugin directories ready: dir=" + compose.pluginsDir("smp").getParent());
        terminal.log("next: dev up");
    }

    private void copyExample(final Path target) {
        try {
            Files.copy(root.resolve(EXAMPLE), target);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot copy " + EXAMPLE, e);
        }
        terminal.log("env file written: file=" + LocalProject.ENV_FILE + " from=" + EXAMPLE);
    }

    private void generate(final EnvFile env, final Secret secret) {
        if (env.isSet(secret.name())) {
            terminal.log("secret already set, kept: key=" + secret.name());
            return;
        }
        final byte[] bytes = new byte[secret.bytes()];
        random.nextBytes(bytes);
        env.set(secret.name(), HexFormat.of().formatHex(bytes));
        terminal.log("secret generated: key=" + secret.name() + " bytes=" + secret.bytes());
    }

    void ask(final EnvFile env, final LocalQuestions.Question question) {
        while (true) {
            terminal.print("\n\u001b[36m[dev]\u001b[0m " + question.prompt() + "\n        " + question.hint()
                    + "\n        > ");
            final boolean secret = question.kind() == LocalQuestions.Kind.OPTIONAL_SECRET;
            final Optional<String> typed = secret ? terminal.readSecret() : terminal.readLine();
            final String answer = typed.orElse("").strip();
            if (question.kind() == LocalQuestions.Kind.LICENCE) {
                if (!LocalQuestions.isYes(answer)) {
                    throw new Processes.Failure("Setup stopped: the Minecraft EULA was not accepted. Delete "
                            + LocalProject.ENV_FILE + " and run dev init again.");
                }
                env.set(question.name(), "true");
                terminal.log("licence accepted: key=" + question.name());
                return;
            }
            if (answer.isEmpty()) {
                if (question.kind() != LocalQuestions.Kind.PLAIN) {
                    terminal.log("answer skipped: key=" + question.name());
                    return;
                }
                if (typed.isEmpty()) {
                    throw new Processes.Failure("Setup stopped: the input ended before " + question.name()
                            + " was answered. Delete " + LocalProject.ENV_FILE + " and run dev init again.");
                }
                terminal.warn("This one needs an answer.");
                continue;
            }
            if (!question.check().test(answer)) {
                terminal.warn("That answer has the wrong form.");
                continue;
            }
            final LocalQuestions.Home home = question.home();
            if (home == null) {
                env.set(question.name(), answer);
                terminal.log("answer written: key=" + question.name() + " file=" + LocalProject.ENV_FILE);
            } else {
                final Path directory = compose.secretsDir(home.service());
                createDirectories(directory);
                EnvFile.readableByItsService(directory.resolve("secrets.env")).set(home.name(), answer);
                terminal.log("answer written: key=" + question.name() + " file=" + root.relativize(directory)
                        + "/secrets.env");
            }
            return;
        }
    }

    /**
     * Derives {@code STEWARD_WEBAUTHN_RP_ID} from the address. steward refuses to start when the two disagree.
     *
     * steward refuses a single label other than {@code localhost}, so the command warns about it here.
     */
    private void deriveRelyingParty(final EnvFile env) {
        final Optional<String> url = env.value("STEWARD_PUBLIC_URL").filter(LocalQuestions::looksLikeBrowserUrl);
        if (url.isEmpty()) {
            return;
        }
        final String host = LocalQuestions.hostOf(url.get());
        env.set("STEWARD_WEBAUTHN_RP_ID", host);
        terminal.log("relying party id derived: key=STEWARD_WEBAUTHN_RP_ID host=" + host);
        if (!host.contains(".") && !host.equals("localhost")) {
            terminal.warn("Steward refuses the host " + host + ": it needs a dot, or must be localhost. In "
                    + LocalProject.ENV_FILE + ", set STEWARD_PUBLIC_URL to http://localhost:5173 for the dev server"
                    + " or http://steward.localhost:8080 for the container, and STEWARD_WEBAUTHN_RP_ID to its host.");
        }
    }

    private static void createDirectories(final Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot create " + directory, e);
        }
    }
}
