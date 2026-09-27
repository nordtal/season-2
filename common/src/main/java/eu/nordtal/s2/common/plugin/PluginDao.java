package eu.nordtal.s2.common.plugin;

import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL surface of {@code service_plugin}; {@link PluginDirectory} is the API. */
@RegisterRowMapper(ManagedPluginMapper.class)
interface PluginDao {

    /** Returns every added plugin, ordered by service and artefact so each keeps its place between reads. */
    @SqlQuery("SELECT * FROM service_plugin ORDER BY service, artifact")
    List<ManagedPlugin> all();

    @SqlQuery("SELECT * FROM service_plugin WHERE service = :service ORDER BY artifact")
    List<ManagedPlugin> on(@Bind("service") String service);

    /** Writes the row, or refreshes {@code file_prefix} on the one already there while keeping {@code added}. */
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
            @Bind("iconUrl") @Nullable String iconUrl,
            @Bind("pageUrl") @Nullable String pageUrl,
            @Bind("addedBy") @Nullable String addedBy);

    /** Returns how many rows went away; zero when it was not added. */
    @SqlUpdate("DELETE FROM service_plugin WHERE service = :service AND artifact = :artifact")
    int remove(@Bind("service") String service, @Bind("artifact") String artifact);
}
