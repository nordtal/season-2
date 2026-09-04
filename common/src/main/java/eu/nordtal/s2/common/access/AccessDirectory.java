package eu.nordtal.s2.common.access;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The season 2 access system, as seen by everything that is not the bot's Discord code.
 * <p>
 * <b>The database is the source of truth</b> for access, donor status and language; Discord roles
 * are a projection of it and LuckPerms is not involved. This interface is that truth, and the
 * proxy, the plugins and the bot all read it through here rather than each writing their own SQL.
 * See {@code docs/access-system.md}.
 * </p>
 *
 * <h2>What lives here and not in a caller</h2>
 * <ul>
 *   <li><b>The append rule.</b> {@link #grantAccess(String, int, AccessSource, UUID)} computes
 *       {@code valid_from = max(now, current valid_until)} in a single SQL statement. A purchase
 *       and an admin grant must not each carry their own copy of that rule.</li>
 *   <li><b>The login decision.</b> {@link #accessState(UUID)} answers linked / member / active
 *       <b>and the current season phase</b> in <b>one</b> query - {@code docs/season-phases.md}
 *       requires that single round trip - and {@link AccessState#mayJoin()} is the decision itself,
 *       phase included.</li>
 * </ul>
 *
 * <h2>Platform</h2>
 * Nothing here refers to Paper, Velocity or Adventure, and nothing on this API refers to JDBI or
 * HikariCP either - the factories take a {@link DataSource} or a JDBC URL, both JDK types. A
 * consumer shades JDBI, HikariCP and the PostgreSQL driver, and never jcore.
 *
 * <h2>Lifetime</h2>
 * One instance per process. {@link #using(DataSource)} borrows a pool somebody else owns (the bot
 * hands in jcore's {@code Database#dataSource()}); {@link #open(String, String, String)} creates
 * and owns one, and {@link #close()} then shuts it down. Closing a borrowed one does nothing.
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
     * Opens a connection pool of its own. This is what the proxy and any plugin uses - they have
     * no jcore and no {@code Database}.
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
     * {@code eu.nordtal.s2.common.phase.PhaseDirectory#currentPhase()} next to this one -
     * {@code docs/season-phases.md} pins the login path to a single round trip, and
     * {@link AccessState#phase()} is how the phase arrives on it.
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
     * Mirrors the Discord admin role into the database, the same way language, membership and donor
     * status already are. An admin is appointed in Discord and is an admin everywhere; there is no
     * second list, and LuckPerms is not involved - see
     * {@code docs/season-phases.md#how-an-admin-is-recognised}.
     * <p>
     * Unlike {@link #setDonor(String, boolean)} this is set <b>and cleared</b>: it is a permission,
     * so losing the Discord role has to lose it. The flag is read back through
     * {@link AccessState#admin()}, on the query the login path makes anyway.
     * </p>
     *
     * @param discordId the Discord snowflake
     * @param admin     whether that account currently holds the Discord admin role
     */
    void setAdmin(String discordId, boolean admin);

    /**
     * Every Discord account that currently holds the admin flag.
     *
     * <p>What the proxy re-reads when it is told the flag moved, so that a revocation reaches a
     * player who is already online. One query for the whole set, because re-deriving every
     * session's flag from it is idempotent and costs the same whether one changed or ten.</p>
     *
     * @return the ids, possibly empty
     */
    java.util.Set<String> admins();

    /**
     * Every admin's Minecraft account, for the three Paper servers.
     *
     * <p>The proxy asks {@link #admins()} because it knows its sessions by Discord id. A backend
     * knows only a {@link UUID}, so it asks this - one query for the whole set, re-derived rather
     * than patched, so a lost notification costs latency and not correctness. It is what
     * {@code AdminOperators#refresh} is fed on every {@code nordtal_admin} signal and every poll
     * tick, and therefore what makes a revoked admin stop being an operator without disconnecting.
     *
     * <p>Admins without an account link are absent, not null: they cannot be online anywhere.</p>
     *
     * @return the Minecraft accounts, possibly empty
     */
    java.util.Set<UUID> adminMinecraftAccounts();

    /**
     * The purchase this Discord account has started and not finished, if any.
     *
     * <p>Read-only, and read from outside the bot on purpose: an admin in game asking "why can this
     * person not get in?" needs to tell "they have not paid" apart from "they are in the middle of
     * paying". See {@link OpenPayment}, which also carries why {@code bunq_tab_id IS NULL} matters.
     *
     * <p><b>Blocking.</b> Never on a main thread and never on a login path - nothing on the login
     * path asks this, and nothing should.</p>
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
     * Appends a period of access: it starts at {@code max(now, current valid_until)} and runs for
     * {@code days} days. Renewing early therefore never loses paid time.
     * <p>
     * The whole rule is one SQL statement evaluated against PostgreSQL's clock. Callers pass how
     * many days were bought and nothing else.
     * </p>
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
     * Issues a link code for an unlinked Minecraft account, or hands back the one already live.
     * <p>
     * "One per UUID, a repeat attempt returns the same code" is enforced by
     * {@code link_code.mc_uuid} being {@code UNIQUE} in the database, not by anything in this
     * class - see {@code AccessDao#upsertLinkCode}. Nothing here checks whether {@code mcUuid} is
     * already linked; the proxy only calls this for the unlinked branch of the login decision, and
     * a code for an already-linked account is simply never redeemable (redemption still enforces
     * the 1:1).
     * </p>
     *
     * @param mcUuid the Minecraft account attempting to join
     * @param ttl    how long a freshly minted code stays valid; ignored when an unexpired code
     *               already exists for this account
     * @return the live code
     * @throws IllegalArgumentException if {@code ttl} is not positive
     */
    LinkCode issueLinkCode(UUID mcUuid, Duration ttl);

    /**
     * Redeems a code typed into the link modal in Discord: validates it, checks expiry, enforces
     * the 1:1 (the database's constraints are what actually enforce it - see
     * {@link #link(String, UUID)}), writes {@code account_link} and deletes the code, all in one
     * transaction. The code is left in place on any failure, so a wrong click does not burn a
     * legitimate retry.
     *
     * @param discordId the Discord account submitting the code
     * @param code      what they typed
     * @return the outcome; never throws for an invalid or already-claimed code
     */
    LinkRedemption redeemLinkCode(String discordId, String code);
}
