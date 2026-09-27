package eu.nordtal.s2.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * {@code init}: writes {@code deploy/dev.env} from the example, generates its four secrets and asks the rest.
 *
 * The example stays the base: some three hundred lines of local paths, ports and profiles nobody should be
 * asked about. What a person is asked is {@link LocalQuestions}, and nothing else.
 */
final class Setup {

    private static final String EXAMPLE = "deploy/dev.env.example";

    /** The secrets generated rather than asked, with how many random bytes each gets. */
    private static final List<Secret> SECRETS = List.of(
            new Secret("POSTGRES_PASSWORD", 24),
            new Secret("VELOCITY_FORWARDING_SECRET", 24),
            new Secret("STEWARD_API_TOKEN", 32),
            new Secret("STEWARD_DEPLOYER_TOKEN", 32));

    private record Secret(String name, int bytes) {}

    private final Path root;
    private final Compose compose;
    private final Terminal terminal;
    private final SecureRandom random = new SecureRandom();

    Setup(final Path root, final Compose compose, final Terminal terminal) {
        this.root = root;
        this.compose = compose;
        this.terminal = terminal;
    }

    void init() {
        final EnvFile env = compose.env();
        if (env.exists()) {
            terminal.log(Compose.ENV_FILE + " already exists; leaving it alone");
        } else {
            copyExample(env.path());
            SECRETS.forEach(secret -> generate(env, secret));
            // Reading containers and creating them are different privileges; one token for both is no boundary.
            if (env.value("STEWARD_API_TOKEN").equals(env.value("STEWARD_DEPLOYER_TOKEN"))) {
                throw new Processes.Failure("STEWARD_API_TOKEN and STEWARD_DEPLOYER_TOKEN came out the same.");
            }
            LocalQuestions.ALL.forEach(question -> ask(env, question));
            deriveRelyingParty(env);
            final List<Integer> leftovers = env.replaceMeLines();
            if (!leftovers.isEmpty()) {
                terminal.warn("REPLACE_ME is still in " + Compose.ENV_FILE + " at line(s): " + leftovers
                        + ". Nothing generated a value for those.");
            }
        }
        for (final String service : ResetGuard.SERVICES) {
            createDirectories(compose.pluginsDir(service));
        }
        createDirectories(compose.packRoot());
        terminal.log("plugin directories: " + compose.pluginsDir("smp") + " and its three siblings - jars and"
                + " configs live there");
        terminal.log("next: dev up");
    }

    private void copyExample(final Path target) {
        try {
            Files.copy(root.resolve(EXAMPLE), target);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot copy " + EXAMPLE, e);
        }
        terminal.log("wrote " + Compose.ENV_FILE + " from " + EXAMPLE);
    }

    private void generate(final EnvFile env, final Secret secret) {
        if (env.isSet(secret.name())) {
            terminal.log(secret.name() + " is already set (left alone)");
            return;
        }
        final byte[] bytes = new byte[secret.bytes()];
        random.nextBytes(bytes);
        env.set(secret.name(), HexFormat.of().formatHex(bytes));
        terminal.log(secret.name() + " generated (" + secret.bytes() + " random bytes, hex)");
    }

    /** Asks one question until the answer is usable, skipped, or the input ends. */
    void ask(final EnvFile env, final LocalQuestions.Question question) {
        while (true) {
            terminal.print("\n\u001b[36m[dev]\u001b[0m " + question.prompt() + "\n        " + question.hint()
                    + "\n        > ");
            final boolean secret = question.kind() == LocalQuestions.Kind.OPTIONAL_SECRET;
            final Optional<String> typed = secret ? terminal.readSecret() : terminal.readLine();
            final String answer = typed.orElse("").strip();
            if (question.kind() == LocalQuestions.Kind.LICENCE) {
                if (!LocalQuestions.isYes(answer)) {
                    throw new Processes.Failure("the EULA was not accepted, so there are no servers to start. "
                            + Compose.ENV_FILE + " has been written as far as this point and can be removed.");
                }
                env.set(question.name(), "true");
                terminal.log(question.name() + " accepted and recorded");
                return;
            }
            if (answer.isEmpty()) {
                if (question.kind() != LocalQuestions.Kind.PLAIN) {
                    terminal.log(question.name() + " left empty - the feature that needs it is simply not served");
                    return;
                }
                if (typed.isEmpty()) {
                    throw new Processes.Failure(question.name() + " is required and the input ended.");
                }
                terminal.warn("that one cannot be left empty.");
                continue;
            }
            if (!question.check().test(answer)) {
                terminal.warn("that does not look like it can be right. Try again.");
                continue;
            }
            env.set(question.name(), answer);
            terminal.log(question.name() + " written to " + Compose.ENV_FILE);
            return;
        }
    }

    /**
     * Derives {@code STEWARD_WEBAUTHN_RP_ID} from the address, which steward-ui refuses to start without agreeing.
     *
     * A single label other than {@code localhost} is refused at startup, so it is said here instead.
     */
    private void deriveRelyingParty(final EnvFile env) {
        final Optional<String> url = env.value("STEWARD_UI_PUBLIC_URL").filter(LocalQuestions::looksLikeBrowserUrl);
        if (url.isEmpty()) {
            return;
        }
        final String host = LocalQuestions.hostOf(url.get());
        env.set("STEWARD_WEBAUTHN_RP_ID", host);
        terminal.log("STEWARD_WEBAUTHN_RP_ID = " + host + " (derived from the address above)");
        if (!host.contains(".") && !host.equals("localhost")) {
            terminal.warn(host + " has no dot in it, so steward-ui will refuse to start: a relying party id has to"
                    + " be a registrable domain, and localhost is the only single label that is allowed. Use"
                    + " http://localhost:5173 for the dev server, http://steward.localhost:8080 for the container."
                    + " Everything else in " + Compose.ENV_FILE + " is written and only this one line needs"
                    + " changing.");
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
