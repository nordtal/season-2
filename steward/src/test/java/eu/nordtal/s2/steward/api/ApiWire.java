package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import java.util.List;

/** The records the routes of this package answer and read, for the generated TypeScript types. */
public final class ApiWire {

    /** Every record a route here answers or reads at its top level. */
    public static final List<Class<?>> ROOTS = List.of(
            StackApi.ServiceTable.class,
            StackApi.Host.class,
            StackApi.Schedule.class,
            AgentWire.Archive.class,
            AgentWire.Plugins.class,
            AgentWire.PluginSearch.class);

    private ApiWire() {}
}
