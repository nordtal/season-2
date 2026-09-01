package eu.nordtal.s2.updater.run;

import eu.nordtal.s2.updater.apply.Applier;
import eu.nordtal.s2.updater.apply.ApplyResult;
import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.http.Downloads;
import eu.nordtal.s2.updater.http.Http;
import eu.nordtal.s2.updater.http.JdkHttp;
import eu.nordtal.s2.updater.plan.Resolver;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.source.GitHubReleases;
import eu.nordtal.s2.updater.source.Modrinth;
import eu.nordtal.s2.updater.source.PaperFill;

import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;

/**
 * The two things the updater actually does, assembled in one place.
 * <p>
 * There are two callers - the command line ({@code UpdaterMain}) and the daemon
 * ({@code serve.Runner}) - and they must behave identically. A request that arrives from a button
 * in Discord has to produce byte for byte the report that {@code updater apply} prints on the host,
 * or the two surfaces are quietly two different programs. Building the resolver twice, in two
 * files, is how that stops being true after the first change to either.
 * </p>
 */
public final class Runs {

    private Runs() {
    }

    /**
     * Asks every source what the newest thing is and compares it with what is in the volumes.
     * Writes nothing.
     */
    public static @NotNull UpdatePlan resolve(final @NotNull UpdaterSpec config) {
        final Http http = new JdkHttp(Duration.ofSeconds(config.httpTimeoutSeconds()), config.githubToken());
        return new Resolver(
                config,
                new GitHubReleases(http),
                new Modrinth(http),
                new PaperFill(http),
                Clock.systemUTC()).resolve();
    }

    /**
     * Fetches everything the plan calls for and moves it into place. Restarts nothing.
     *
     * <p><b>Migrate before calling this.</b> The order is the design: a plugin must never come up
     * against a schema older than itself, and a failed migration has to stop the run while nothing
     * has moved yet.</p>
     */
    public static @NotNull ApplyResult apply(final @NotNull UpdaterSpec config,
                                             final @NotNull UpdatePlan plan) {
        return new Applier(config, new Downloads(Duration.ofSeconds(config.downloadTimeoutSeconds())))
                .apply(plan);
    }
}
