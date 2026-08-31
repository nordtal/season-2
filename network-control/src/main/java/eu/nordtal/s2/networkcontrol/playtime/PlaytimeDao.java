package eu.nordtal.s2.networkcontrol.playtime;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The whole SQL surface of the play-time counter, as a JDBI SqlObject interface - the same style as
 * {@code :common}'s {@code AccessDao}, and package-private for the same reason: {@link PlaytimeStore}
 * is the API and no caller should hold a {@code Jdbi} of ours.
 * <p>
 * <b>This lives in {@code network-control} and not in {@code :common} on purpose.</b>
 * {@code player_playtime} is written by exactly one process - only the proxy sees a session across
 * servers, a backend sees just its own slice (docs/smp.md#prestige--a-crest-earned-by-time). The
 * DDL is in {@code :common} because that is where all the DDL lives
 * (docs/architecture.md#schema-ownership); the writer is here because this is the only thing that
 * writes it. When the SMP plugin comes to <em>read</em> these seconds for the prestige crest, that
 * read is a different query with a different owner.
 * </p>
 */
interface PlaytimeDao {

    /**
     * Adds a slice of online time to a player's running total.
     *
     * <h2>Why it is an addition and not a write</h2>
     * {@code V4} says it in the schema comment: "{@code seconds bigint}, not an {@code interval}
     * [...] makes the accumulate-on-disconnect write a plain addition that two proxies could not
     * disagree about." Two flushes racing produce the sum of both slices, in either order, without
     * a read-modify-write window in the JVM to lose one in. A writer that computed
     * {@code total = previousTotal + slice} in Java and then wrote {@code total} would silently
     * drop one of the two.
     *
     * <h2>Why the row is created here rather than by the bot</h2>
     * {@code player_playtime} has no row until somebody plays, and the proxy is what notices that
     * first. {@code ON CONFLICT} makes the first flush of a season and the ten-thousandth the same
     * statement. The foreign key onto {@code discord_user} still holds, which is why this is only
     * ever called with a Discord id the login query returned.
     *
     * @param discordId the linked Discord account - the key of this table, not the Minecraft UUID
     * @param seconds   how many seconds to add; the caller never passes zero or less
     * @return 1
     */
    @SqlUpdate("""
            INSERT INTO player_playtime (discord_id, seconds, updated)
            VALUES (:discordId, :seconds, now())
            ON CONFLICT (discord_id) DO UPDATE
                SET seconds = player_playtime.seconds + EXCLUDED.seconds,
                    updated = now()
            """)
    int add(@Bind("discordId") String discordId, @Bind("seconds") long seconds);
}
