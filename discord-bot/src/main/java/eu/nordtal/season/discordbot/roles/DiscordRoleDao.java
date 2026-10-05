package eu.nordtal.season.discordbot.roles;

import java.util.Map;
import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The Discord role the bot found or created for each role it uses, in {@code discord_role}. */
public interface DiscordRoleDao {

    /** Returns every stored role id by what it stands for. */
    @SqlQuery("SELECT role_key, role_id FROM discord_role")
    @KeyColumn("role_key")
    @ValueColumn("role_id")
    Map<String, String> all();

    /** Stores the role found for {@code key}, replacing the one that was gone. */
    @SqlUpdate("""
            INSERT INTO discord_role (role_key, role_id, adopted)
            VALUES (:key, :roleId, now())
            ON CONFLICT (role_key) DO UPDATE SET role_id = EXCLUDED.role_id, adopted = now()
            """)
    void store(@Bind("key") String key, @Bind("roleId") String roleId);
}
