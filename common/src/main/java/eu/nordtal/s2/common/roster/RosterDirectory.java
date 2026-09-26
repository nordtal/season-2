package eu.nordtal.s2.common.roster;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The access schema as a list: everyone, every payment, every grant of one person.
 *
 * <b>Why this is not on {@code AccessDirectory}</b>
 *
 * That interface is a per-person operational API - grant, revoke, link, "may this UUID join" - and
 * every method on it is on somebody's critical path. This one answers "show me the table", which is
 * a different question with a different shape: it is bounded by a row count instead of by an
 * identity, it returns whole rows instead of decisions, and nothing in the network depends on it.
 * Keeping them apart keeps a page of a web interface from being able to reach for
 * {@code revokeAccess}.
 *
 * It is in {@code :common} because {@code :common} owns the schema. A second module writing its
 * own SQL against {@code discord_user} and {@code access_grant} would be a second place that goes
 * stale when a migration lands, and it would go stale silently.
 *
 * <b>Read-only, and only read-only</b>
 *
 * Every statement behind this interface is a {@code SELECT}. Nothing here creates, updates or
 * deletes a row; a caller that needs to change something calls
 * {@code eu.nordtal.s2.common.access.AccessDirectory}.
 *
 * <b>Blocking.</b> Every method is a database round trip. Never call one from a Paper server's
 * main thread - see the rule in {@code :common}'s README - although in practice the caller is a web
 * request thread in the Steward interface.
 *
 * Nothing on this API refers to Paper, Velocity, Adventure, JDBI or HikariCP, and every record it
 * returns is built from JDK types only, so it serialises to JSON without an adapter per record.
 *
 * Holds no resource of its own: it borrows the pool it is given, so there is nothing to close.
 */
public interface RosterDirectory {

    /**
     * @param dataSource the pool - the same one the caller already reads access or the phase through
     * @return a directory over that pool
     */
    static RosterDirectory using(final DataSource dataSource) {
        return new JdbiRosterDirectory(dataSource);
    }

    /**
     * Everyone the bot knows, newest change first.
     *
     * One statement, not one per person: the account link and both access columns are joined in.
     * See {@link Person} for what {@code accessUntil} and {@code accessActive} each mean, and why
     * they disagree about a revoked grant.
     *
     * Ordered by {@code discord_user.updated} descending - the row's own timestamp, which the bot
     * touches on every mirror of a Discord role, a locale or a member state. It is not "newest
     * member", and there is no column that would answer that: nothing records when a person was
     * first seen.
     *
     * @param limit how many, at most. A page, not an export; a number below 1 is clamped to 1
     * @return the people, possibly empty. Somebody with no link and no grant is <b>present</b>, with
     *         nulls in those components - a list of everyone that quietly omits the people who never
     *         got anywhere is the wrong answer to every question it is asked
     */
    List<Person> people(int limit);

    /** Returns the row {@link #people(int)} would print for this account, or empty for an unknown id. */
    Optional<Person> personOf(String discordId);

    /**
     * Every payment request, newest first - open, paid, expired, cancelled and superseded alike.
     *
     * @param limit how many, at most; a number below 1 is clamped to 1
     * @return the requests, possibly empty
     */
    List<Payment> payments(int limit);

    /**
     * The payment requests still waiting to be paid, oldest first.
     *
     * Unbounded, and the second method here that is. It exists so that an interface offering
     * {@code /access settle} can offer a <em>list</em> rather than a field: a reference is six
     * characters with no meaning, and typing one from memory on the one command that books money is
     * a mistake nobody needs. A limit would make the list quietly incomplete, which is worse than
     * long.
     *
     * @return the open requests, possibly empty
     */
    List<Payment> openPayments();

    /**
     * Every access grant of one person, newest first, including expired and revoked ones.
     *
     * Unbounded on purpose, and it is the only method here that is: a grant is written when
     * somebody buys access or an admin hands it out, so one person's list is a handful of rows over
     * a season. The other two read tables that grow without anybody deciding to.
     *
     * @param discordId the Discord snowflake
     * @return the grants, oldest last; empty for somebody who has never had access, and equally
     *         empty for a Discord id nobody has ever heard of
     */
    List<Grant> grantsOf(String discordId);
}
