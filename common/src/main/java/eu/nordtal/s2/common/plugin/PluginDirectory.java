package eu.nordtal.s2.common.plugin;

import java.util.List;
import javax.sql.DataSource;

/**
 * The plugins an admin added on top of the ones the code gives.
 *
 * <b>Why this is a directory and not a config file</b>
 *
 * Because the whole point of the feature is that it survives a redeployment nobody edits a file
 * for. {@code Topology.SERVICES} is code because the plugins on it are not a deployment's decision;
 * these are exactly a deployment's decision, they change between one Tuesday and the next, and they
 * are chosen by somebody clicking a button in a browser. A YAML file in a volume would put them
 * where the browser cannot reach and where a second copy of the list would grow.
 *
 * <b>A row is a wish, not an observation.</b> Nothing here says what is installed - the jars on
 * disk say that, and {@code Installation} reads them on every run. This list is what
 * {@code Resolver} merges into the topology, which is how an added plugin joins the ordinary update
 * cycle instead of being a second mechanism.
 *
 * Nothing here names Paper, Velocity, JDA, JDBI or HikariCP: the factory takes a
 * {@link DataSource} and every process hands in the pool it already owns.
 */
public interface PluginDirectory {

    /** The directory without a database, answering the fixed topology unchanged rather than throwing. */
    PluginDirectory NONE = new PluginDirectory() {};

    /**
     * @param dataSource the pool - the same one this process already reads the update inbox through
     * @return a directory over that pool. Holds no resource of its own, so there is nothing to close
     */
    static PluginDirectory using(final DataSource dataSource) {
        return new JdbiPluginDirectory(dataSource);
    }

    /** Every added plugin, on every service, ordered by service and then artefact. */
    default List<ManagedPlugin> all() {
        return List.of();
    }

    /** The added plugins on one service. */
    default List<ManagedPlugin> on(final String service) {
        return all().stream().filter(plugin -> plugin.service().equals(service)).toList();
    }

    /**
     * Adds a plugin to a service, or refreshes the row that is already there.
     *
     * Unlike {@link #all()} this has no harmless default: a directory that cannot write and
     * pretends it did is a button that reports success and installs nothing, forever. Loud beats
     * silent, so the default throws and the one real directory overrides it.
     */
    default void add(final ManagedPlugin plugin) {
        throw new UnsupportedOperationException("this directory cannot add a plugin: " + plugin.artifact());
    }

    /**
     * Takes the row away. Doing it twice is not an error.
     *
     * This removes the <em>wish</em> and nothing else. Deleting the jar and the plugin's data
     * folder is steward-worker's, because only steward-worker has the volumes mounted - and it is
     * deliberately the same button, so a row cannot outlive its files or the other way round.
     */
    default void remove(final String service, final String artifact) {
        throw new UnsupportedOperationException("this directory cannot remove a plugin: " + artifact);
    }
}
