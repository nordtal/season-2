package eu.nordtal.s2.steward.run;

import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.steward.apply.Applier;
import eu.nordtal.s2.steward.apply.ApplyResult;
import eu.nordtal.s2.steward.config.StewardSpec;
import eu.nordtal.s2.steward.http.Http;
import eu.nordtal.s2.steward.http.SourceHttp;
import eu.nordtal.s2.steward.plan.Resolver;
import eu.nordtal.s2.steward.plan.UpdatePlan;
import eu.nordtal.s2.steward.source.Downloads;
import eu.nordtal.s2.steward.source.GitHubReleases;
import eu.nordtal.s2.steward.source.Modrinth;
import eu.nordtal.s2.steward.source.PaperFill;
import java.time.Duration;

/** Resolve and apply, built in one place so the command line and the daemon produce the same report. */
public final class Runs {

    private Runs() {}

    /** Compares what every source calls newest with what is in the volumes, writing nothing. */
    public static UpdatePlan resolve(final StewardSpec config) {
        return resolve(config, eu.nordtal.s2.steward.plugin.PluginDirectory.NONE);
    }

    /** The same, with the plugins an admin added merged in, which every caller with a database uses. */
    public static UpdatePlan resolve(
            final StewardSpec config, final eu.nordtal.s2.steward.plugin.PluginDirectory plugins) {
        final Http http = SourceHttp.over(SourceHttp.client(
                Duration.ofSeconds(config.httpTimeoutSeconds()),
                config.githubToken(),
                Waiting.on(NetworkTime.clock())));
        return new Resolver(
                        config,
                        new GitHubReleases(http),
                        new Modrinth(http),
                        new PaperFill(http),
                        NetworkTime.clock(),
                        plugins)
                .resolve();
    }

    /**
     * Fetches everything the plan calls for and moves it into place, restarting nothing.
     *
     * Migrate first, so a plugin never meets an older schema and a failed migration stops the run early.
     */
    public static ApplyResult apply(final StewardSpec config, final UpdatePlan plan) {
        return new Applier(
                        config,
                        new Downloads(SourceHttp.client(
                                Duration.ofSeconds(config.downloadTimeoutSeconds()),
                                "",
                                Waiting.on(NetworkTime.clock()))))
                .apply(plan);
    }
}
