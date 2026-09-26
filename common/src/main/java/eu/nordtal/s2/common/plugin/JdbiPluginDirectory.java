package eu.nordtal.s2.common.plugin;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * The only implementation of {@link PluginDirectory}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 * <p>
 * It borrows the pool it is given and owns nothing, which is why there is no {@code close()} here
 * and none on the interface - the process that built the pool closes the pool.
 * </p>
 */
final class JdbiPluginDirectory implements PluginDirectory {

    private final PluginDao dao;

    JdbiPluginDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(PluginDao.class);
    }

    @Override
    public List<ManagedPlugin> all() {
        return dao.all();
    }

    @Override
    public List<ManagedPlugin> on(final String service) {
        // The query, not the default's filter over all(): one service's list is the common read
        // (every service page does it) and it must not drag the whole table across the wire.
        return dao.on(Objects.requireNonNull(service, "service"));
    }

    @Override
    public void add(final ManagedPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        dao.add(
                plugin.service(),
                plugin.artifact(),
                plugin.projectId(),
                plugin.filePrefix(),
                plugin.title(),
                plugin.iconUrl(),
                plugin.pageUrl(),
                plugin.addedBy());
    }

    @Override
    public void remove(final String service, final String artifact) {
        dao.remove(Objects.requireNonNull(service, "service"), Objects.requireNonNull(artifact, "artifact"));
    }
}
