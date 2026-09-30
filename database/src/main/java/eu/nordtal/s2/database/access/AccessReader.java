package eu.nordtal.s2.database.access;

import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * What every process reads about a member: link, access, language, profiles, admins and the roster.
 *
 * One instance per process, over a connection pool the caller owns and closes.
 */
public interface AccessReader {

    /**
     * Uses a connection pool the caller owns.
     *
     * @param dataSource the pool, e.g. a plugin's own HikariCP pool
     * @param clock      when link codes expire
     * @return a reader over that pool
     */
    static AccessReader using(final DataSource dataSource, final InstantSource clock) {
        return JdbiAccessDirectory.borrowing(dataSource, clock);
    }

    /** Returns the Minecraft account linked to this Discord id, if any. */
    Optional<UUID> linkedMinecraftAccount(String discordId);

    /** Returns the Discord account linked to this Minecraft account, if any. */
    Optional<String> linkedDiscordAccount(UUID mcUuid);

    /**
     * Returns the access state and the season phase in one round trip.
     *
     * @param mcUuid the Minecraft account attempting to join
     * @return the state, with {@link AccessState#linked()} {@code false} for an unlinked UUID; the phase is always set
     */
    AccessState accessState(UUID mcUuid);

    /**
     * Returns the player's language, English when unknown.
     *
     * Never throws: a missing translation must not break a disconnect screen.
     */
    Locale locale(UUID mcUuid);

    /** Returns whether the permanent donor flag is set, {@code false} for an unknown user. */
    boolean isDonor(String discordId);

    /**
     * Returns the Discord profile last observed for this account, or {@link DiscordProfile#EMPTY}.
     *
     * Never throws. A name is not a key, so there is no lookup by name.
     */
    DiscordProfile discordProfile(String discordId);

    /** Returns the Minecraft name last seen at login for this Discord id, or {@link MinecraftProfile#EMPTY}. */
    MinecraftProfile minecraftProfile(String discordId);

    /** Returns every grant of one user, newest window first, expired and revoked ones included. */
    List<AccessGrant> grantsOf(String discordId);

    /** Returns every Discord account that currently holds the admin flag. */
    Set<String> admins();

    /**
     * Returns the Minecraft account of every admin who has one linked.
     *
     * The whole set is re-read rather than patched, so a lost notification costs latency and not correctness.
     */
    Set<UUID> adminMinecraftAccounts();

    /**
     * Returns the purchase this Discord account has started and not finished, if any.
     * Blocking: never call it on a main thread or on the login path.
     *
     * @param discordId the Discord snowflake
     * @return the newest {@code OPEN} request, or empty
     */
    Optional<OpenPayment> openPayment(String discordId);

    /**
     * Returns everyone the bot knows with their link and access, one row per person.
     * Newest update first, with the Discord id breaking ties so a page is stable across calls.
     */
    List<Person> people(int limit);

    /** Returns the one row {@link #people(int)} would print for a single account. */
    Optional<Person> personOf(String discordId);
}
