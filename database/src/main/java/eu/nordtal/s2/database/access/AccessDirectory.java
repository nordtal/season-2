package eu.nordtal.s2.database.access;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * What the writers of access need on top of {@link AccessReader}: the bot, Steward's grants and the proxy's login.
 *
 * One instance per process, over a connection pool the caller owns and closes.
 */
public interface AccessDirectory extends AccessReader {

    /** Uses a connection pool the caller owns. */
    static AccessDirectory using(final DataSource dataSource, final InstantSource clock) {
        return JdbiAccessDirectory.borrowing(dataSource, clock);
    }

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
