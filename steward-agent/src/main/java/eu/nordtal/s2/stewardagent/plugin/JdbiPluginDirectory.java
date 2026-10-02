package eu.nordtal.s2.stewardagent.plugin;

import eu.nordtal.s2.database.Jdbis;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** The only implementation of {@link PluginDirectory}; it borrows the pool and owns nothing. */
final class JdbiPluginDirectory implements PluginDirectory {

    private final PluginDao dao;

    JdbiPluginDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(PluginDao.class);
    }

    @Override
    public List<ManagedPlugin> all() {
        return dao.all();
    }

    @Override
    public List<ManagedPlugin> on(final String service) {
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
        dao.forget(service, artifact);
    }

    @Override
    public void installed(final String service, final String artifact, final String fileName, final String release) {
        dao.installed(service, artifact, fileName, release);
    }

    @Override
    public java.util.Map<String, String> releases(final String service) {
        return dao.releases(Objects.requireNonNull(service, "service"));
    }
}
