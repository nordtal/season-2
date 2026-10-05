package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The wheel's spins: today's free one, the earned ones, and putting back one that paid nothing; never on main. */
public interface SpinDao {

    @SqlQuery("""
            SELECT granted AS granted, used AS used, last_free AS lastFree
            FROM smp_spin
            WHERE discord_id = :discordId
            """)
    @RegisterConstructorMapper(Spins.class)
    Optional<Spins> spinsOf(@Bind("discordId") DiscordId discordId);

    /**
     * Takes today's free spin, once.
     *
     * The {@code last_free IS DISTINCT FROM :today} guard gives only one of two simultaneous clicks a prize.
     */
    @SqlQuery("""
            INSERT INTO smp_spin (discord_id, last_free)
            VALUES (:discordId, :today)
            ON CONFLICT (discord_id) DO UPDATE
                SET last_free = :today
                WHERE smp_spin.last_free IS DISTINCT FROM :today
            RETURNING discord_id
            """)
    Optional<String> takeFreeSpin(@Bind("discordId") DiscordId discordId, @Bind("today") java.time.LocalDate today);

    /** Spends one earned spin, and only if there is one to spend. */
    @SqlQuery("""
            UPDATE smp_spin
            SET used = used + 1
            WHERE discord_id = :discordId AND used < granted
            RETURNING discord_id
            """)
    Optional<String> takeEarnedSpin(@Bind("discordId") DiscordId discordId);

    /**
     * Puts back a free spin that was taken and paid out nothing.
     *
     * Idempotent; {@code previous} is null for a player's first ever free spin, hence the {@code CAST}.
     */
    @SqlUpdate("""
            UPDATE smp_spin
            SET last_free = CAST(:previous AS date)
            WHERE discord_id = :discordId AND last_free = :today
            """)
    void restoreFreeSpin(
            @Bind("discordId") DiscordId discordId,
            @Bind("previous") java.time.@Nullable LocalDate previous,
            @Bind("today") java.time.LocalDate today);

    /**
     * Puts back an earned spin that paid out nothing.
     *
     * Not idempotent: a second call hands back a second spin, so each call site runs at most once per spin.
     */
    @SqlUpdate("UPDATE smp_spin SET used = used - 1 WHERE discord_id = :discordId AND used > 0")
    void restoreEarnedSpin(@Bind("discordId") DiscordId discordId);

    @SqlUpdate("""
            INSERT INTO smp_spin (discord_id, granted)
            VALUES (:discordId, :count)
            ON CONFLICT (discord_id) DO UPDATE
                SET granted = smp_spin.granted + excluded.granted
            """)
    void grantSpins(@Bind("discordId") DiscordId discordId, @Bind("count") int count);
}
