package eu.nordtal.s2.stewardagent.plugin;

import java.util.List;
import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.config.ValueColumn;
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

    /** Notes the file an artefact runs from now, replacing the one before it. */
    @SqlUpdate("""
            INSERT INTO plugin_file (service, artifact, file_name, release)
            VALUES (:service, :artifact, :fileName, :release)
            ON CONFLICT (service, artifact) DO UPDATE
                SET file_name = EXCLUDED.file_name, release = EXCLUDED.release, installed_at = now()
            """)
    void installed(
            @Bind("service") String service,
            @Bind("artifact") String artifact,
            @Bind("fileName") String fileName,
            @Bind("release") String release);

    @SqlQuery("SELECT file_name, release FROM plugin_file WHERE service = :service")
    @KeyColumn("file_name")
    @ValueColumn("release")
    java.util.Map<String, String> releases(@Bind("service") String service);

    /** Drops the note of a removed plugin's file. */
    @SqlUpdate("DELETE FROM plugin_file WHERE service = :service AND artifact = :artifact")
    void forget(@Bind("service") String service, @Bind("artifact") String artifact);

    /** Returns how many rows went away; zero when it was not added. */
    @SqlUpdate("DELETE FROM service_plugin WHERE service = :service AND artifact = :artifact")
    int remove(@Bind("service") String service, @Bind("artifact") String artifact);
}
