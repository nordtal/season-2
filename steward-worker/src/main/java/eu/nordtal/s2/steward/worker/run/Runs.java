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

/** Resolve and apply, built in one place so the command line and the daemon produce the same report. */
public final class Runs {

    private Runs() {}

    /** Compares what every source calls newest with what is in the volumes, writing nothing. */
    public static UpdatePlan resolve(final StewardSpec config) {
        return resolve(config, eu.nordtal.s2.common.plugin.PluginDirectory.NONE);
    }

    /** The same, with the plugins an admin added merged in, which every caller with a database uses. */
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
     * Fetches everything the plan calls for and moves it into place, restarting nothing.
     *
     * Migrate first, so a plugin never meets an older schema and a failed migration stops the run early.
     */
    public static ApplyResult apply(final StewardSpec config, final UpdatePlan plan) {
        return new Applier(config, new Downloads(Duration.ofSeconds(config.downloadTimeoutSeconds()))).apply(plan);
    }
}
