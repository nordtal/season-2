package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.ImageResult;
import java.util.List;
import java.util.Map;

/** The records the routes of this package answer and read, for the generated TypeScript types. */
public final class ApiWire {

    /** Every record a route here answers or reads at its top level. */
    public static final List<Class<?>> ROOTS = List.of(
            StackApi.ServiceTable.class,
            StackApi.Host.class,
            StackApi.Schedule.class,
            AgentWire.Archive.class,
            AgentWire.Plugins.class,
            AgentWire.PluginSearch.class,
            AgentWire.Resolve.class,
            ActionEntry.Action.class,
            StackApi.RestoreAsked.class,
            PluginsForward.RemovalAsked.class);

    /** Records here whose own simple name would say too little or collide. */
    public static final Map<Class<?>, String> NAMES = Map.of(
            AgentWire.Archive.class, "Backup",
            AgentWire.Plugin.class, "ServicePlugin",
            AgentWire.Plugins.class, "ServicePlugins",
            AgentWire.Resolve.class, "Available",
            AgentWire.ResolvedChange.class, "AvailableChange",
            ImageResult.State.class, "ImageState");

    private ApiWire() {}
}
