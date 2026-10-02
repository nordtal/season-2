package eu.nordtal.s2.stewardagent.plugin;

import eu.nordtal.s2.internalapi.agent.Topology;
import java.util.List;
import javax.sql.DataSource;

/**
 * The plugins an admin added on top of the ones the code gives.
 *
 * A row is a wish: the jars on disk say what is installed, and {@code Resolver} merges these rows into the topology.
 */
public interface PluginDirectory {

    /** The directory without a database, answering the fixed topology unchanged. */
    PluginDirectory NONE = new PluginDirectory() {};

    /** Returns a directory over {@code dataSource}; it holds no resource of its own. */
    static PluginDirectory using(final DataSource dataSource) {
        return new JdbiPluginDirectory(dataSource);
    }

    /** Returns every added plugin, ordered by service and then artefact. */
    default List<ManagedPlugin> all() {
        return List.of();
    }

    /** Returns the added plugins on one service. */
    default List<ManagedPlugin> on(final String service) {
        return all().stream().filter(plugin -> plugin.service().equals(service)).toList();
    }

    /**
     * Adds a plugin to a service, or refreshes its row.
     *
     * The default throws, so a directory that cannot write never reports success.
     */
    default void add(final ManagedPlugin plugin) {
        throw new UnsupportedOperationException("this directory cannot add a plugin: " + plugin.artifact());
    }

    /**
     * Removes the row; removing it twice is not an error.
     *
     * Deleting the jar and data folder is steward's, which has the volumes mounted.
     */
    default void remove(final String service, final String artifact) {
        throw new UnsupportedOperationException("this directory cannot remove a plugin: " + artifact);
    }

    /**
     * The four services with every plugin an admin added folded in.
     *
     * @param added every row of {@code service_plugin}; unknown services and fixed artefacts are ignored, and all are
     *     optional
     * @return the same four services, in the same order, each carrying its own extra plugins
     */
    public static List<Topology.Service> servicesWith(final java.util.Collection<ManagedPlugin> added) {
        if (added.isEmpty()) {
            return Topology.SERVICES;
        }
        final List<Topology.Service> merged = new java.util.ArrayList<>(Topology.SERVICES.size());
        for (final Topology.Service service : Topology.SERVICES) {
            final List<String> plugins = new java.util.ArrayList<>(service.plugins());
            final List<String> optional = new java.util.ArrayList<>(service.optional());
            for (final ManagedPlugin plugin : added) {
                if (!plugin.service().equals(service.name())) {
                    continue;
                }
                final String artifact = Topology.addedArtifact(plugin.artifact(), service.kind());
                if (plugins.contains(artifact)) {
                    continue;
                }
                plugins.add(artifact);
                optional.add(artifact);
            }
            merged.add(
                    plugins.size() == service.plugins().size()
                            ? service
                            : new Topology.Service(service.name(), service.kind(), plugins, optional));
        }
        return List.copyOf(merged);
    }
}
