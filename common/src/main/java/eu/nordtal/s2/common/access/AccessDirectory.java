package eu.nordtal.s2.common.access;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The season 2 access system, as seen by everything that is not the bot's Discord code.
 *
 * One instance per process; closing it shuts down a pool it opened and leaves a borrowed one alone.
 */
public interface AccessDirectory extends AutoCloseable {

    /**
     * Uses a connection pool the caller owns. The pool is not closed by {@link #close()}.
     *
     * @param dataSource the pool, e.g. jcore's {@code Database#dataSource()} inside the bot
     * @return a directory over that pool
     */
    static AccessDirectory using(final DataSource dataSource) {
        return JdbiAccessDirectory.borrowing(dataSource);
    }

    /**
     * Opens a connection pool of its own, as the proxy and the plugins do.
     *
     * @param jdbcUrl  a {@code jdbc:postgresql://...} URL
     * @param username the database user
     * @param password the database password
     * @return a directory owning a new pool; {@link #close()} shuts it down
     */
    static AccessDirectory open(final String jdbcUrl, final String username, final String password) {
        return JdbiAccessDirectory.owning(jdbcUrl, username, password);
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

    /** Returns every grant of one user, oldest window first, expired and revoked ones included. */
    List<AccessGrant> grantsOf(String discordId);

    /** Ensures {@code discord_user} has a row for this account; every other write has a foreign key onto it. */
    void ensureUser(String discordId);

    /** Records guild membership as the bot just observed it. */
    void setMemberState(String discordId, MemberState memberState);

    /**
     * Stores the language mirrored from the Discord onboarding role.
     * Only the language is kept, so {@code de-AT} and {@code de-DE} are one value.
     */
    void setLocale(String discordId, Locale locale);

    /** Sets the permanent donor flag; the bot only ever sets it to {@code true}. */
    void setDonor(String discordId, boolean donor);

    /**
     * Sets this account's total play time to exactly {@code seconds}, creating the row if it is missing.
     * This is the one lever that moves the prestige tier.
     *
     * @param seconds never negative; the column's CHECK refuses it
     */
    void setPlaytimeSeconds(String discordId, long seconds);

    /**
     * Writes the global username and the guild nickname and avatar at once.
     *
     * @param displayName the guild nickname, {@code null} when the member has not set one
     * @param avatarUrl   the guild avatar, {@code null} when the member has none
     */
    void setDiscordProfile(String discordId, String username, String displayName, String avatarUrl);

    /** Clears the guild nickname and avatar of an account that left or was banned, keeping the username. */
    void clearGuildProfile(String discordId);

    /** Returns every Discord account that currently holds the admin flag. */
    java.util.Set<String> admins();

    /**
     * Returns the Minecraft account of every admin who has one linked.
     *
     * The whole set is re-read rather than patched, so a lost notification costs latency and not correctness.
     */
    java.util.Set<UUID> adminMinecraftAccounts();

    /**
     * Returns the purchase this Discord account has started and not finished, if any.
     * Blocking: never call it on a main thread or on the login path.
     *
     * @param discordId the Discord snowflake
     * @return the newest {@code OPEN} request, or empty
     */
    java.util.Optional<OpenPayment> openPayment(String discordId);

    /**
     * Writes the 1:1 link between a Discord and a Minecraft account.
     *
     * @return {@code false} when either side was already linked, including a losing concurrent attempt
     */
    boolean link(String discordId, UUID mcUuid);

    /** Removes the link of this Discord account and returns whether one existed. */
    boolean unlink(String discordId);

    /**
     * Writes the Minecraft name last seen at login, keyed by the account.
     *
     * @return whether a linked account existed to write it onto
     */
    boolean setMinecraftName(UUID mcUuid, String name);

    /**
     * Appends a period of access starting at {@code max(now, current valid_until)}.
     *
     * @param days             how many days were bought, must be positive
     * @param paymentRequestId the request that paid for it, {@code null} for an admin grant
     * @return the grant that was written, with the window PostgreSQL computed
     * @throws IllegalArgumentException if {@code days} is not positive
     */
    AccessGrant grantAccess(String discordId, int days, AccessSource source, @Nullable UUID paymentRequestId);

    /**
     * Revokes every non-revoked grant of this user that has not yet run out.
     *
     * @return how many grants were revoked
     */
    int revokeAccess(String discordId);

    /** Releases the connection pool if this instance owns one. Idempotent. */
    @Override
    void close();

    /**
     * Issues a link code for a Minecraft account, or returns the one already live.
     *
     * @param ttl how long a new code stays valid; ignored when a live code exists
     * @throws IllegalArgumentException if {@code ttl} is not positive
     */
    LinkCode issueLinkCode(UUID mcUuid, Duration ttl);

    /**
     * Redeems a code typed into the Discord link modal, in one transaction.
     *
     * The code is kept on any failure, so a wrong click does not burn a retry. Never throws for an invalid code.
     */
    LinkRedemption redeemLinkCode(String discordId, String code);
}
