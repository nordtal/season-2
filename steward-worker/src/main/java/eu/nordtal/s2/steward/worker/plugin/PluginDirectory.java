package eu.nordtal.s2.steward.worker.plugin;

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
     * Deleting the jar and data folder is steward-worker's, which has the volumes mounted.
     */
    default void remove(final String service, final String artifact) {
        throw new UnsupportedOperationException("this directory cannot remove a plugin: " + artifact);
    }
}
