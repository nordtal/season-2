package eu.nordtal.s2.smp.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the SMP reads and writes, as one JDBI SqlObject.
 *
 * <p><b>Never called from the main thread.</b> That rule was written into this repository on
 * 2026-09-01 after {@code /hg start} was found doing exactly that: a Paper server blocked on a
 * database round trip is a server that has stopped ticking, and a slow query becomes a visible
 * freeze for everybody. Every caller here hops to an async task first and comes back to the main
 * thread only to touch the world.
 *
 * <p>The keys are {@code discord_id}, never the Minecraft UUID: the UUID reaches these tables
 * through {@code account_link}, and storing it twice would create a second answer to "whose account
 * is this".
 */
public interface SmpDao {

    // ---------------------------------------------------------------- identity

    @SqlQuery("SELECT discord_id FROM account_link WHERE mc_uuid = :mcUuid")
    Optional<String> discordIdOf(@Bind("mcUuid") UUID mcUuid);

    @SqlQuery("SELECT mc_uuid FROM account_link WHERE discord_id = :discordId")
    Optional<UUID> mcUuidOf(@Bind("discordId") String discordId);

    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    Optional<String> localeOf(@Bind("discordId") String discordId);

    /**
     * Whether this account holds the Discord admin flag.
     *
     * <p>Mirrored into {@code discord_user} by the bot and only read here. There is no LuckPerms
     * anywhere in this repository and no second admin list (docs/smp.md#admins).
     */
    @SqlQuery("""
            SELECT usr.admin
            FROM account_link link
                     JOIN discord_user usr ON usr.discord_id = link.discord_id
            WHERE link.mc_uuid = :mcUuid
            """)
    Optional<Boolean> isAdmin(@Bind("mcUuid") UUID mcUuid);

    // ---------------------------------------------------------------- milestones

    /**
     * The keys of every milestone that is finished.
     *
     * <p>What each of them <em>unlocked</em> is not stored: that is the track's business and lives
     * in {@code milestones.yml}. The database holds progress, the file holds definition, and a key
     * here that the file no longer declares is exactly what {@code TrackValidation} exists to
     * catch.
     */
    @SqlQuery("SELECT key FROM smp_milestone WHERE state = 'COMPLETE' ORDER BY key")
    List<String> completedMilestoneKeys();

    @SqlQuery("SELECT key FROM smp_milestone WHERE state = 'ACTIVE' LIMIT 1")
    Optional<String> activeMilestoneKey();

    @SqlQuery("SELECT state FROM smp_milestone WHERE key = :key")
    Optional<String> milestoneState(@Bind("key") String key);

    /**
     * Writes a milestone row if the track declares one this database has never seen.
     *
     * <p>Idempotent on purpose: the track is reloadable while players are online, and a milestone
     * appended to the file mid-season has to appear without anybody running SQL by hand.
     */
    @SqlUpdate("""
            INSERT INTO smp_milestone (key, state)
            VALUES (:key, :state)
            ON CONFLICT (key) DO NOTHING
            """)
    void ensureMilestone(@Bind("key") String key, @Bind("state") String state);
}
