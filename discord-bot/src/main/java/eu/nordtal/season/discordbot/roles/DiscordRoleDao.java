package eu.nordtal.season.discordbot.roles;

import java.util.Map;
import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** Reads and writes {@code discord_role}, which holds the Discord role id of each role the bot uses. */
public interface DiscordRoleDao {

    @SqlQuery("SELECT role_key, role_id FROM discord_role")
    @KeyColumn("role_key")
    @ValueColumn("role_id")
    Map<String, String> all();

    /** Stores the role id of {@code key}. An existing id is replaced. */
    @SqlUpdate("""
            INSERT INTO discord_role (role_key, role_id, adopted)
            VALUES (:key, :roleId, now())
            ON CONFLICT (role_key) DO UPDATE SET role_id = EXCLUDED.role_id, adopted = now()
            """)
    void store(@Bind("key") String key, @Bind("roleId") String roleId);
}
