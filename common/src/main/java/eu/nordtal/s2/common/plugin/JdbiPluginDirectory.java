package eu.nordtal.s2.common.plugin;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * The only implementation of {@link PluginDirectory}.
 *
 * It borrows the pool it is given and owns nothing, so there is no {@code close()}.
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
        // Its own query, so one service's list does not read the whole table.
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
