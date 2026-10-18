package eu.nordtal.season.stewardagent.plugin;

import eu.nordtal.season.internalapi.agent.Topology;
import java.util.List;
import javax.sql.DataSource;

/**
 * The plugins an admin added on top of those the code gives.
 *
 * A row is a wish: the jars on disk say what is installed, and {@code Resolver} merges these rows into the topology.
 */
public interface PluginDirectory {

    /** The directory without a database, answering the fixed topology unchanged. */
    PluginDirectory NONE = new PluginDirectory() {};

    static PluginDirectory using(final DataSource dataSource) {
        return new JdbiPluginDirectory(dataSource);
    }

    default List<ManagedPlugin> all() {
        return List.of();
    }

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
     * Deletes the row. Deleting it twice is no error.
     *
     * Deleting the jar and data folder is steward's, which has the volumes mounted.
     */
    default void remove(final String service, final String artifact) {
        throw new UnsupportedOperationException("this directory cannot remove a plugin: " + artifact);
    }

    /**
     * Notes the file a run moved into place for one artefact, and the release whose run that was.
     *
     * A directory without a database records nothing: the note is for reading, and a run never depends on it.
     */
    default void installed(final String service, final String artifact, final String fileName, final String release) {}

    /**
     * Returns the release that installed each file of one service, by file name, empty where none was recorded.
     */
    default java.util.Map<String, String> releases(final String service) {
        return java.util.Map.of();
    }

    /**
     * Returns the servers with every plugin an admin added.
     *
     * @param servers the servers as the labels of compose.yml describe them
     * @param added every row of {@code service_plugin}. Unknown services and fixed artefacts are ignored.
     * @return the same servers in the same order, each with its extra plugins
     */
    public static List<Topology.Service> servicesWith(
            final List<Topology.Service> servers, final java.util.Collection<ManagedPlugin> added) {
        if (added.isEmpty()) {
            return servers;
        }
        final List<Topology.Service> merged = new java.util.ArrayList<>(servers.size());
        for (final Topology.Service service : servers) {
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
                            : new Topology.Service(
                                    service.name(), service.kind(), plugins, optional, service.prefixes()));
        }
        return List.copyOf(merged);
    }
}
