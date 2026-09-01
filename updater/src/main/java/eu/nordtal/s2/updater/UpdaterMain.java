package eu.nordtal.s2.updater;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.updater.config.Configs;
import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.http.Http;
import eu.nordtal.s2.updater.http.JdkHttp;
import eu.nordtal.s2.updater.plan.Report;
import eu.nordtal.s2.updater.plan.Resolver;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.source.GitHubReleases;
import eu.nordtal.s2.updater.source.Modrinth;
import eu.nordtal.s2.updater.source.PaperFill;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Entry point.
 *
 * <h2>What it does today, and what it will do</h2>
 * Step 1 of docs/updater.md and no more: resolve, compare, print, exit. It touches nothing a server
 * reads - not a jar, not a database row, not a file in a Minecraft volume - which makes it safe to
 * run against a live deployment at any moment, and that is the point of building this step on its
 * own. The one file it does write is its own {@code config/updater.yml}, on a first run, in its own
 * volume.
 *
 * <p>Steps 2 to 6 turn this process into a long-lived one: it will apply the schema, listen on
 * PostgreSQL for a request from the bot or from {@code /smp update}, swap the jars and offer a
 * restart. Until then it is a one-shot command, and a container that runs it should be started on
 * demand rather than left {@code restart: unless-stopped} - an update check is not a service.</p>
 *
 * <h2>Exit codes</h2>
 * {@code 0} when a report was produced, whatever the report says - including one full of rows that
 * could not be checked, because that <em>is</em> the answer and it is in the text. {@code 1} only
 * when no report could be produced at all, which in practice means a config this module refuses.
 * Deliberately not "non-zero when an update is available": that would make every scheduler treat a
 * pending update as a failure, and this module's first rule is that nothing updates on a schedule.
 */
@Slf4j
public final class UpdaterMain {

    /** Mirrors the bot's layout: WORKDIR /app, config in a volume at /app/config. */
    private static final String DEFAULT_CONFIG_DIR = "config";

    private UpdaterMain() {
    }

    public static void main(final String[] args) {
        final Path configDirectory = Path.of(
                System.getenv().getOrDefault("NORDTAL_UPDATER_CONFIG_DIR", DEFAULT_CONFIG_DIR));

        final UpdaterSpec config;
        try {
            config = Configs.updater(configDirectory, LoggerFactory.getLogger(Configs.class)).get();
        } catch (final ConfigException broken) {
            // Named file, named setting, no stack trace: this is the one error an operator is
            // expected to fix, and a 40-line trace above the sentence is how it gets missed.
            log.error("Refusing to run on a config that cannot be read: {}", broken.getMessage());
            System.exit(1);
            return;
        }

        final Http http = new JdkHttp(Duration.ofSeconds(config.httpTimeoutSeconds()), config.githubToken());
        final Resolver resolver = new Resolver(
                config,
                new GitHubReleases(http),
                new Modrinth(http),
                new PaperFill(http),
                Clock.systemUTC());

        final UpdatePlan plan = resolver.resolve();

        // stdout, not the logger: this is the program's output, not a record of it running. A
        // report wrapped in timestamps and thread names is a report nobody pastes anywhere.
        System.out.println(Report.render(plan));
    }
}
