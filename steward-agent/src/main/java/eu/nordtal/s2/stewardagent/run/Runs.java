package eu.nordtal.s2.stewardagent.run;

import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.stewardagent.apply.Applier;
import eu.nordtal.s2.stewardagent.apply.ApplyResult;
import eu.nordtal.s2.stewardagent.config.RunSpec;
import eu.nordtal.s2.stewardagent.plan.Resolver;
import eu.nordtal.s2.stewardagent.plan.UpdatePlan;
import eu.nordtal.s2.stewardagent.source.Downloads;
import eu.nordtal.s2.stewardagent.source.GitHubReleases;
import eu.nordtal.s2.stewardagent.source.Http;
import eu.nordtal.s2.stewardagent.source.Modrinth;
import eu.nordtal.s2.stewardagent.source.PaperFill;
import eu.nordtal.s2.stewardagent.source.SourceHttp;
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
            final eu.nordtal.s2.stewardagent.plugin.PluginDirectory plugins,
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
     * Migrate first, so a plugin never meets an older schema and a failed migration stops the run early.
     */
    public static ApplyResult apply(
            final RunSpec config,
            final AgentWire.Topology topology,
            final UpdatePlan plan,
            final SettingStore settings) {
        return new Applier(
                        config,
                        new Downloads(SourceHttp.client(
                                Duration.ofSeconds(config.downloadTimeoutSeconds()),
                                "",
                                Waiting.on(NetworkTime.clock()))),
                        settings,
                        topology)
                .apply(plan);
    }
}
