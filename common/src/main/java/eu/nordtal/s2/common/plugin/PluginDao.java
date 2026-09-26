package eu.nordtal.s2.common.plugin;

import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The whole SQL surface of {@code service_plugin}, as a JDBI SqlObject interface - the same style
 * as {@code UpdateDao} and {@code AccessDao}.
 * <p>
 * Package-private on purpose: {@link PluginDirectory} is the API, this is how it is implemented,
 * and no consumer should ever hold a {@code Jdbi} or a DAO of ours.
 * </p>
 */
@RegisterRowMapper(ManagedPluginMapper.class)
interface PluginDao {

    /**
     * Every added plugin, on every service.
     *
     * <p>Ordered by service and then by artefact rather than by when it was added: this list is
     * merged into the topology on every run and read into a table in the interface, and both want
     * the same plugin in the same place twice running.</p>
     */
    @SqlQuery("SELECT * FROM service_plugin ORDER BY service, artifact")
    List<ManagedPlugin> all();

    @SqlQuery("SELECT * FROM service_plugin WHERE service = :service ORDER BY artifact")
    List<ManagedPlugin> on(@Bind("service") String service);

    /**
     * Writes the row, or refreshes the one already there.
     *
     * <p>{@code ON CONFLICT DO UPDATE} rather than an insert that can fail: adding a plugin that is
     * already added is not an error, it is somebody making sure - and the newer press carries the
     * newer {@code file_prefix}, which is the field that goes stale when a project renames its
     * artefact. {@code added} is left alone on a refresh: it says when the plugin arrived on this
     * service, and that did not change.</p>
     */
    @SqlUpdate("""
            INSERT INTO service_plugin
                (service, artifact, project_id, file_prefix, title, icon_url, page_url, added_by)
            VALUES (:service, :artifact, :projectId, :filePrefix, :title, :iconUrl, :pageUrl, :addedBy)
            ON CONFLICT (service, artifact) DO UPDATE
                SET project_id = EXCLUDED.project_id,
                    file_prefix = EXCLUDED.file_prefix,
                    title = EXCLUDED.title,
                    icon_url = EXCLUDED.icon_url,
                    page_url = EXCLUDED.page_url,
                    added_by = EXCLUDED.added_by
            """)
    void add(
            @Bind("service") String service,
            @Bind("artifact") String artifact,
            @Bind("projectId") String projectId,
            @Bind("filePrefix") String filePrefix,
            @Bind("title") String title,
            @Bind("iconUrl") String iconUrl,
            @Bind("pageUrl") String pageUrl,
            @Bind("addedBy") String addedBy);

    /** @return how many rows went away; zero when it was not added, which is not an error */
    @SqlUpdate("DELETE FROM service_plugin WHERE service = :service AND artifact = :artifact")
    int remove(@Bind("service") String service, @Bind("artifact") String artifact);
}
