package eu.nordtal.s2.steward.worker.run;

import eu.nordtal.s2.steward.worker.apply.Applier;
import eu.nordtal.s2.steward.worker.apply.ApplyResult;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import eu.nordtal.s2.steward.worker.http.Downloads;
import eu.nordtal.s2.steward.worker.http.Http;
import eu.nordtal.s2.steward.worker.http.JdkHttp;
import eu.nordtal.s2.steward.worker.plan.Resolver;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import eu.nordtal.s2.steward.worker.source.GitHubReleases;
import eu.nordtal.s2.steward.worker.source.Modrinth;
import eu.nordtal.s2.steward.worker.source.PaperFill;
import java.time.Clock;
import java.time.Duration;

/**
 * The two things steward-worker actually does, assembled in one place.
 * <p>
 * There are two callers - the command line ({@code StewardWorker}) and the daemon
 * ({@code serve.Runner}) - and they must behave identically. A request that arrives from a button
 * in Discord has to produce byte for byte the report that {@code steward-worker apply} prints on
 * the host, or the two surfaces are quietly two different programs. Building the resolver twice,
 * in two files, is how that stops being true after the first change to either.
 * </p>
 */
public final class Runs {

    private Runs() {}

    /**
     * Asks every source what the newest thing is and compares it with what is in the volumes.
     * Writes nothing.
     */
    public static UpdatePlan resolve(final StewardSpec config) {
        return resolve(config, eu.nordtal.s2.common.plugin.PluginDirectory.NONE);
    }

    /**
     * The same, with the plugins an admin added from the interface merged in (season-2-ops/129).
     *
     * <p><b>Every caller that has a database hands one in</b>, and that is not optional style: the
     * whole reason this class exists is that the command line and the daemon must produce the same
     * report, and a resolve that leaves the added plugins out is a second program. The overload
     * above exists for the one caller that genuinely has no pool - and it answers
     * {@code PluginDirectory#NONE}, which resolves exactly what this method resolved before the
     * table existed.</p>
     */
    public static UpdatePlan resolve(
            final StewardSpec config, final eu.nordtal.s2.common.plugin.PluginDirectory plugins) {
        final Http http = new JdkHttp(Duration.ofSeconds(config.httpTimeoutSeconds()), config.githubToken());
        return new Resolver(
                        config,
                        new GitHubReleases(http),
                        new Modrinth(http),
                        new PaperFill(http),
                        Clock.systemUTC(),
                        plugins)
                .resolve();
    }

    /**
     * Fetches everything the plan calls for and moves it into place. Restarts nothing.
     *
     * <p><b>Migrate before calling this.</b> The order is the design: a plugin must never come up
     * against a schema older than itself, and a failed migration has to stop the run while nothing
     * has moved yet.</p>
     */
    public static ApplyResult apply(final StewardSpec config, final UpdatePlan plan) {
        return new Applier(config, new Downloads(Duration.ofSeconds(config.downloadTimeoutSeconds()))).apply(plan);
    }
}
