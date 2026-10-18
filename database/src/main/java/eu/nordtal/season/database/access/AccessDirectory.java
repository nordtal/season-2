package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The write side of access on top of {@link AccessReader}: the bot, Steward's grants and the proxy's login.
 * One instance per process, over a connection pool that the caller owns and closes.
 */
public interface AccessDirectory extends AccessReader {

    /** Uses a connection pool that the caller owns. */
    static AccessDirectory using(final DataSource dataSource, final InstantSource clock) {
        return JdbiAccessDirectory.borrowing(dataSource, clock);
    }

    /** Creates the {@code discord_user} row if it is missing. Every other write has a foreign key onto it. */
    void ensureUser(DiscordId discordId);

    void setMemberState(DiscordId discordId, MemberState memberState);

    /** Stores the language of the language role of the member, null for the network's. */
    void setLocale(DiscordId discordId, @Nullable Locale locale);

    /** Stores the time zone of the region role of the member, null for the network's. */
    void setTimeZone(DiscordId discordId, @Nullable ZoneId zone);

    /**
     * Sets the total play time of this account to {@code seconds} and creates the row if it is missing.
     * It is the one lever that moves the prestige tier.
     *
     * @param seconds never negative. The CHECK of the column refuses it
     */
    void setPlaytimeSeconds(DiscordId discordId, long seconds);

    /**
     * Writes the global username and the guild nickname and avatar at once.
     *
     * @param displayName the guild nickname, null if unset
     * @param avatarUrl the guild avatar, null if none
     */
    void setDiscordProfile(DiscordId discordId, String username, String displayName, String avatarUrl);

    /** Clears the guild nickname and avatar of an account that left or was banned. The username stays. */
    void clearGuildProfile(DiscordId discordId);

    /**
     * Writes the 1:1 link between a Discord and a Minecraft account.
     *
     * @return false if either side was linked already, including a losing concurrent attempt
     */
    boolean link(DiscordId discordId, UUID mcUuid);

    boolean unlink(DiscordId discordId);

    /**
     * Writes the Minecraft name last seen at login.
     *
     * @return whether a linked account existed
     */
    boolean setMinecraftName(UUID mcUuid, String name);

    /**
     * Appends a period of access starting at {@code max(now, current valid_until)}.
     *
     * @param days the days bought, positive
     * @param paymentRequestId the request that paid for it, null for an admin grant
     * @return the grant written, with the window that PostgreSQL computed
     * @throws IllegalArgumentException if {@code days} is not positive
     */
    AccessGrant grantAccess(DiscordId discordId, int days, AccessSource source, @Nullable UUID paymentRequestId);

    /**
     * Revokes every grant of this user that is not revoked and has not run out.
     *
     * @return the grants revoked
     */
    int revokeAccess(DiscordId discordId);

    /**
     * Issues a link code for a Minecraft account or returns the live one.
     *
     * @param ttl how long a new code stays valid. Ignored if a live code exists
     * @throws IllegalArgumentException if {@code ttl} is not positive
     */
    LinkCode issueLinkCode(UUID mcUuid, Duration ttl);

    /**
     * Redeems a code typed into the Discord link modal, in one transaction.
     *
     * A failure keeps the code, so a wrong click does not burn a retry. An invalid code never throws.
     */
    LinkRedemption redeemLinkCode(DiscordId discordId, String code);
}
