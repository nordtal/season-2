package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.stewardagent.apply.Applier;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.plan.Resolver;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import eu.nordtal.season.stewardagent.source.Downloads;
import eu.nordtal.season.stewardagent.source.GitHubReleases;
import eu.nordtal.season.stewardagent.source.Http;
import eu.nordtal.season.stewardagent.source.Modrinth;
import eu.nordtal.season.stewardagent.source.PaperFill;
import eu.nordtal.season.stewardagent.source.SourceHttp;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Resolve and apply, built in one place so the command line and the daemon produce the same report. */
public final class Runs {

    private Runs() {}

    /**
     * Compares what every source calls newest with what is installed, writing nothing.
     *
     * @param topology what compose.yml's labels say, which names the servers and their plugins
     * @param plugins the plugins an admin added, merged into the topology
     * @param settings where the proxy's pack is read; {@code null} without a database, which leaves the pack unknown
     */
    public static UpdatePlan resolve(
            final RunSpec config,
            final AgentWire.Topology topology,
            final eu.nordtal.season.stewardagent.plugin.PluginDirectory plugins,
            final @Nullable SettingStore settings) {
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
                        topology.servers(),
                        plugins,
                        settings)
                .resolve();
    }

    /**
     * Fetches everything the plan calls for and moves it into place, restarting nothing.
     *
     * Every file moved into place is noted in {@code plugins} with this agent's release, which is the run's.
     */
    public static ApplyResult apply(
            final RunSpec config,
            final AgentWire.Topology topology,
            final UpdatePlan plan,
            final SettingStore settings,
            final eu.nordtal.season.stewardagent.plugin.PluginDirectory plugins) {
        final ApplyResult result = new Applier(
                        config,
                        new Downloads(SourceHttp.client(
                                Duration.ofSeconds(config.downloadTimeoutSeconds()),
                                "",
                                Waiting.on(NetworkTime.clock()))),
                        settings,
                        topology)
                .apply(plan);
        record(result, Release.ownVersion(), plugins);
        return result;
    }

    /** Notes every file the apply moved into place; nothing without a release, which only a test runs as. */
    static void record(
            final ApplyResult result,
            final @Nullable String release,
            final eu.nordtal.season.stewardagent.plugin.PluginDirectory plugins) {
        if (release == null) {
            return;
        }
        for (final ApplyResult.Outcome outcome : result.outcomes()) {
            if (outcome.status() == ApplyResult.Status.DONE && outcome.service() != null && outcome.file() != null) {
                plugins.installed(outcome.service(), outcome.artifact(), outcome.file(), release);
            }
        }
    }
}
