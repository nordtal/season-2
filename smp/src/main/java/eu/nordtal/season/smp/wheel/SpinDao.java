package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** SpinDao must not run on the main thread. */
public interface SpinDao {

    @SqlQuery("""
            SELECT granted AS granted, used AS used, last_free AS lastFree
            FROM smp_spin
            WHERE discord_id = :discordId
            """)
    @RegisterConstructorMapper(Spins.class)
    Optional<Spins> spinsOf(@Bind("discordId") DiscordId discordId);

    /** Only one of two simultaneous calls succeeds. */
    @SqlQuery("""
            INSERT INTO smp_spin (discord_id, last_free)
            VALUES (:discordId, :today)
            ON CONFLICT (discord_id) DO UPDATE
                SET last_free = :today
                WHERE smp_spin.last_free IS DISTINCT FROM :today
            RETURNING discord_id
            """)
    Optional<String> takeFreeSpin(@Bind("discordId") DiscordId discordId, @Bind("today") java.time.LocalDate today);

    @SqlQuery("""
            UPDATE smp_spin
            SET used = used + 1
            WHERE discord_id = :discordId AND used < granted
            RETURNING discord_id
            """)
    Optional<String> takeEarnedSpin(@Bind("discordId") DiscordId discordId);

    /** Calling it again changes nothing. */
    @SqlUpdate("""
            UPDATE smp_spin
            SET last_free = CAST(:previous AS date)
            WHERE discord_id = :discordId AND last_free = :today
            """)
    void restoreFreeSpin(
            @Bind("discordId") DiscordId discordId,
            @Bind("previous") java.time.@Nullable LocalDate previous,
            @Bind("today") java.time.LocalDate today);

    /** Each call restores one more spin. */
    @SqlUpdate("UPDATE smp_spin SET used = used - 1 WHERE discord_id = :discordId AND used > 0")
    void restoreEarnedSpin(@Bind("discordId") DiscordId discordId);

    @SqlUpdate("""
            INSERT INTO smp_spin (discord_id, granted)
            VALUES (:discordId, :count)
            ON CONFLICT (discord_id) DO UPDATE
                SET granted = smp_spin.granted + excluded.granted
            """)
    void grantSpins(@Bind("discordId") DiscordId discordId, @Bind("count") int count);

    /** Sets the extra spins left to {@code count}, keeping the count of spins used. */
    @SqlUpdate("""
            INSERT INTO smp_spin (discord_id, granted)
            VALUES (:discordId, :count)
            ON CONFLICT (discord_id) DO UPDATE
                SET granted = smp_spin.used + excluded.granted
            """)
    void setSpins(@Bind("discordId") DiscordId discordId, @Bind("count") int count);
}
