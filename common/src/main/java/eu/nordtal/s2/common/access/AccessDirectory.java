package eu.nordtal.s2.common.access;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The season 2 access system, as seen by everything that is not the bot's Discord code.
 *
 * <p>The database is the source of truth for access, donor status and language; Discord roles are a
 * projection of it. The proxy, the plugins and the bot all read it through here rather than each
 * writing their own SQL, so that the append rule
 * ({@link #grantAccess(String, int, AccessSource, UUID)}) and the login decision
 * ({@link #accessState(UUID)}, one round trip including the season phase) exist once.
 *
 * <p>Nothing on this API refers to Paper, Velocity, Adventure, JDBI or HikariCP - the factories
 * take a {@link DataSource} or a JDBC URL, both JDK types.
 *
 * <p>One instance per process. {@link #using(DataSource)} borrows a pool somebody else owns;
 * {@link #open(String, String, String)} creates and owns one, which {@link #close()} shuts down.
 * Closing a borrowed one does nothing.
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
     * Opens a connection pool of its own - what the proxy and the plugins use.
     *
     * @param jdbcUrl  a {@code jdbc:postgresql://...} URL
     * @param username the database user
     * @param password the database password
     * @return a directory owning a new pool; {@link #close()} shuts it down
     */
    static AccessDirectory open(final String jdbcUrl, final String username, final String password) {
        return JdbiAccessDirectory.owning(jdbcUrl, username, password);
    }

    // ---------------------------------------------------------------- reads

    /**
     * @param discordId the Discord snowflake
     * @return the Minecraft account linked to it, if any
     */
    Optional<UUID> linkedMinecraftAccount(String discordId);

    /**
     * @param mcUuid the Minecraft account
     * @return the Discord account linked to it, if any
     */
    Optional<String> linkedDiscordAccount(UUID mcUuid);

    /**
     * Everything the login path needs, in one round trip: the access state <b>and</b> the season
     * phase. There is deliberately no second call to
     * {@code eu.nordtal.s2.common.phase.PhaseDirectory#currentPhase()} beside it.
     *
     * @param mcUuid the Minecraft account attempting to join
     * @return the state, with {@link AccessState#linked()} {@code false} for a UUID nobody has
     *         linked; the phase is filled in either way, because whether the player is refused and
     *         with which screen depends on it
     */
    AccessState accessState(UUID mcUuid);

    /**
     * The player's language. Never throws and never returns {@code null}: an unlinked account, an
     * unreadable language tag or a database that has never heard of this UUID all yield
     * {@link Locale#ENGLISH}, because a missing translation must not be able to break a
     * disconnect screen.
     *
     * @param mcUuid the Minecraft account
     * @return the locale, English when unknown
     */
    Locale locale(UUID mcUuid);

    /**
     * @param discordId the Discord snowflake
     * @return whether the permanent donor flag is set; {@code false} for an unknown user
     */
    boolean isDonor(String discordId);

    /**
     * What discord-bot last observed this account to be called and pictured as, in the guild. Never
     * {@code null} and never throws: an account nobody has mirrored a profile onto yet, and one
     * nobody has ever heard of, both answer {@link DiscordProfile#EMPTY}.
     *
     * <p><b>{@code discordId} is the only thing this may be looked up by.</b> A name is not a key -
     * two different accounts may hold the exact same username, display name and avatar, see
     * {@link DiscordProfile}'s javadoc - so there is deliberately no method here that resolves an
     * account from a name.
     *
     * @param discordId the Discord snowflake
     * @return the cached profile, {@link DiscordProfile#EMPTY} if nothing has been observed
     */
    DiscordProfile discordProfile(String discordId);

    /**
     * The Minecraft name {@code network-control} last saw at login, for the account linked to this
     * Discord id. Never {@code null}: an unlinked account and one nobody has seen join yet both
     * answer {@link MinecraftProfile#EMPTY}.
     *
     * @param discordId the Discord snowflake
     * @return the cached name, {@link MinecraftProfile#EMPTY} if nothing has been observed
     */
    MinecraftProfile minecraftProfile(String discordId);

    /**
     * Every grant of one user, oldest window first. This is what {@code /access-status} prints.
     *
     * @param discordId the Discord snowflake
     * @return the grants, including expired and revoked ones
     */
    List<AccessGrant> grantsOf(String discordId);

    // ---------------------------------------------------------------- writes

    /**
     * Makes sure {@code discord_user} has a row for this account, with the defaults - English,
     * {@code MEMBER}, not a donor. Every other write has a foreign key onto it.
     *
     * @param discordId the Discord snowflake
     */
    void ensureUser(String discordId);

    /**
     * @param discordId   the Discord snowflake
     * @param memberState guild membership as the bot just observed it
     */
    void setMemberState(String discordId, MemberState memberState);

    /**
     * @param discordId the Discord snowflake
     * @param locale    the language, mirrored from the Discord onboarding role; only the language
     *                  is stored, so {@code de-AT} and {@code de-DE} are one value
     */
    void setLocale(String discordId, Locale locale);

    /**
     * @param discordId the Discord snowflake
     * @param donor     the permanent donor flag; the bot only ever sets it to {@code true}
     */
    void setDonor(String discordId, boolean donor);

    /**
     * Mirrors the Discord admin role into the database. An admin is appointed in Discord and is an
     * admin everywhere; there is no second list.
     *
     * <p>Unlike {@link #setDonor(String, boolean)} this is set <b>and cleared</b>: it is a
     * permission, so losing the Discord role has to lose it.
     *
     * @param discordId the Discord snowflake
     * @param admin     whether that account currently holds the Discord admin role
     */
    void setAdmin(String discordId, boolean admin);

    /**
     * Writes all three Discord-observed fields at once - the global username and the <b>guild</b>
     * nickname and avatar. Called from discord-bot's reconcile pass and its member-join handler,
     * which already visit one member at a time; there is deliberately no separate loop for this
     * (steward/44) unless a measured slowdown of the reconcile forces one.
     *
     * @param discordId   the Discord snowflake
     * @param username    the current global username, without a discriminator
     * @param displayName the current guild nickname, {@code null} when the member has not set one
     * @param avatarUrl   the current guild avatar, {@code null} when the member has none
     */
    void setDiscordProfile(String discordId, String username, String displayName, String avatarUrl);

    /**
     * Clears the two guild-scoped fields - the guild nickname and the guild avatar - because an
     * account that just left or was banned no longer has either in this guild. The global username
     * is left as it was last observed: it is not guild-scoped, so it goes stale rather than wrong.
     *
     * @param discordId the Discord snowflake
     */
    void clearGuildProfile(String discordId);

    /**
     * Every Discord account that currently holds the admin flag - what the proxy re-reads when it
     * is told the flag moved, so a revocation reaches a player who is already online.
     *
     * @return the ids, possibly empty
     */
    java.util.Set<String> admins();

    /**
     * Every admin's Minecraft account, for the Paper servers, which know a session only by
     * {@link UUID}. The whole set is re-read rather than patched, so a lost notification costs
     * latency and not correctness.
     *
     * <p>Admins without an account link are absent: they cannot be online anywhere.
     *
     * @return the Minecraft accounts, possibly empty
     */
    java.util.Set<UUID> adminMinecraftAccounts();

    /**
     * The purchase this Discord account has started and not finished, if any. Read-only.
     *
     * <p><b>Blocking.</b> Never call it on a main thread or on the login path.
     *
     * @param discordId the Discord snowflake
     * @return the newest {@code OPEN} request, or empty
     */
    java.util.Optional<OpenPayment> openPayment(String discordId);

    /**
     * Writes the 1:1 link. Both halves of the 1:1 are enforced by unique constraints in the
     * database, so a losing concurrent attempt returns {@code false} rather than corrupting
     * anything.
     *
     * @param discordId the Discord snowflake
     * @param mcUuid    the Minecraft account
     * @return {@code true} when the link was written, {@code false} when either side was already
     *         linked to something
     */
    boolean link(String discordId, UUID mcUuid);

    /**
     * @param discordId the Discord snowflake
     * @return {@code true} when a link was removed
     */
    boolean unlink(String discordId);

    /**
     * Writes the Minecraft name last seen at login, keyed by the account rather than the name - see
     * {@link MinecraftProfile}. A no-op, not an error, when {@code mcUuid} is not linked to
     * anything: there is nowhere to cache the name of an account nobody has connected to a Discord
     * identity yet.
     *
     * @param mcUuid the Minecraft account that just joined
     * @param name   its current Minecraft name
     * @return whether a linked account existed to write it onto
     */
    boolean setMinecraftName(UUID mcUuid, String name);

    /**
     * Appends a period of access: it starts at {@code max(now, current valid_until)} and runs for
     * {@code days} days, so renewing early never loses paid time. The whole rule is one SQL
     * statement evaluated against PostgreSQL's clock.
     *
     * @param discordId        the Discord snowflake; a row is created for it if needed
     * @param days             how many days were bought, must be positive
     * @param source           purchase or admin grant
     * @param paymentRequestId the request that paid for it, {@code null} for an admin grant
     * @return the grant that was written, with the window PostgreSQL computed
     * @throws IllegalArgumentException if {@code days} is not positive
     */
    AccessGrant grantAccess(String discordId, int days, AccessSource source, UUID paymentRequestId);

    /**
     * Revokes the entire remaining run of access for one user - every non-revoked grant that has
     * not yet run out. Revoking a single grant out of the middle of an appended chain is
     * deliberately not offered; see {@code AccessDao#revokeAccess}.
     *
     * @param discordId the Discord snowflake
     * @return how many grants were revoked
     */
    int revokeAccess(String discordId);

    /** Releases the connection pool if this instance owns one. Idempotent. */
    @Override
    void close();

    // ---------------------------------------------------------------- linking (stage C)

    /**
     * Issues a link code for an unlinked Minecraft account, or hands back the one already live -
     * enforced by {@code link_code.mc_uuid} being {@code UNIQUE}, not by anything in this class.
     *
     * <p>Nothing here checks whether {@code mcUuid} is already linked; a code for a linked account
     * is simply never redeemable, because redemption enforces the 1:1.
     *
     * @param mcUuid the Minecraft account attempting to join
     * @param ttl    how long a freshly minted code stays valid; ignored when an unexpired code
     *               already exists for this account
     * @return the live code
     * @throws IllegalArgumentException if {@code ttl} is not positive
     */
    LinkCode issueLinkCode(UUID mcUuid, Duration ttl);

    /**
     * Redeems a code typed into the link modal in Discord, in one transaction. The code is left in
     * place on any failure, so a wrong click does not burn a legitimate retry.
     *
     * @param discordId the Discord account submitting the code
     * @param code      what they typed
     * @return the outcome; never throws for an invalid or already-claimed code
     */
    LinkRedemption redeemLinkCode(String discordId, String code);
}
