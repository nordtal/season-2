package eu.nordtal.s2.stewardagent;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.Platform;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.stewardagent.config.RunSpec;
import eu.nordtal.s2.stewardagent.plan.PlanView;
import eu.nordtal.s2.stewardagent.plan.PluginsApi;
import eu.nordtal.s2.stewardagent.plugin.PluginDirectory;
import eu.nordtal.s2.stewardagent.run.PluginRemoval;
import eu.nordtal.s2.stewardagent.run.Runs;
import eu.nordtal.s2.stewardagent.source.Modrinth;
import eu.nordtal.s2.stewardagent.source.SourceHttp;
import io.javalin.config.JavalinConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The routes a run's sources answer: what is newest against what is installed, and the plugins on each server.
 *
 * Removing a plugin is not among them: it stops the server first, so it is a run.
 */
final class RunRoutes {

    private final Supplier<RunSpec> config;
    private final PluginDirectory plugins;
    private final SettingStore settings;
    private final PluginsApi pluginsApi;
    private final Supplier<AgentWire.Topology> topology;

    RunRoutes(
            final Supplier<RunSpec> config,
            final PluginDirectory plugins,
            final Database database,
            final Clock clock,
            final Supplier<AgentWire.Topology> topology) {
        this.config = config;
        this.topology = topology;
        this.plugins = plugins;
        this.settings = SettingStore.using(database.dataSource());
        final RunSpec now = config.get();
        this.pluginsApi = new PluginsApi(
                () -> topology.get().servers(),
                plugins,
                new Modrinth(SourceHttp.over(SourceHttp.client(
                        Duration.ofSeconds(now.httpTimeoutSeconds()), now.githubToken(), Waiting.on(clock)))),
                Path.of(now.volumesRoot()),
                Platform.MINECRAFT,
                Map.of(
                        Topology.PACKETEVENTS, now.packetEventsProject(),
                        Topology.VOICE_CHAT, now.voiceChatProject(),
                        Topology.VOICE_CHAT_PROXY, now.voiceChatProject(),
                        Topology.CORE_PROTECT, now.coreProtectProject()),
                clock);
    }

    void register(final JavalinConfig config) {
        config.routes.get(
                AgentWire.PLAN,
                ctx -> ctx.json(PlanView.of(Runs.resolve(this.config.get(), topology.get(), plugins, settings))));
        config.routes.get(AgentWire.PLUGINS, pluginsApi::list);
        config.routes.get(AgentWire.PLUGIN_SEARCH, pluginsApi::search);
        config.routes.post(
                AgentWire.PLUGINS, ctx -> pluginsApi.add(ctx, Objects.requireNonNullElse(ctx.queryParam("by"), "")));
    }

    /** The removal a REMOVE_PLUGIN run carries out while it holds the server stopped. */
    PluginRemoval removal() {
        return new PluginRemoval() {
            @Override
            public boolean has(final String service, final String artifact) {
                return plugins.on(service).stream()
                        .anyMatch(plugin -> plugin.artifact().equals(artifact));
            }

            @Override
            public List<String> remove(final String service, final String artifact) {
                return pluginsApi.remove(service, artifact);
            }
        };
    }
}
