package eu.nordtal.s2.common.audit;

import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The journal: {@code audit_log}, newest first, whole or filtered.
 *
 * <b>What the table is for, and why reading it is its own API</b>
 *
 * {@code audit_log} is the append-only record of everything a human needs to reconstruct - links,
 * unlinks, admin grants and revokes, manual settlements, phase switches. Until there was somewhere
 * to show it, the only reader was whoever opened {@code psql}. It is separate from
 * {@link eu.nordtal.s2.common.roster.RosterDirectory} because it is not a projection of the access
 * schema's current state: the roster answers "how are things", this answers "how did they get that
 * way", and the second question survives a row being deleted.
 *
 * It is in {@code :common} because {@code :common} owns the schema.
 *
 * <b>Two readers and one writer</b>
 *
 * {@link #recent(int)} and {@link #search(String, String, int)} only read. {@link #record} writes
 * one line - as its own statement, deliberately <em>not</em> inside the transaction of the action
 * it describes, for the reason given on that method. A rolled-back grant does not take its journal
 * line with it.
 *
 * <b>Blocking.</b> Every method is a database round trip.
 *
 * Holds no resource of its own: it borrows the pool it is given, so there is nothing to close.
 */
public interface AuditDirectory {

    /**
     * @param dataSource the pool - the same one the caller already reads access or the roster through
     * @return a directory over that pool
     */
    static AuditDirectory using(final DataSource dataSource) {
        return new JdbiAuditDirectory(dataSource);
    }

    /**
     * The journal, newest first.
     *
     * @param limit how many, at most. A page, not an export; a number below 1 is clamped to 1
     * @return the entries, possibly empty
     */
    List<AuditEntry> recent(int limit);

    /**
     * The journal filtered by action, by subject, by both, or by neither.
     *
     * One statement whichever way it is called - the predicates switch themselves off rather than
     * the SQL being assembled from strings. {@code search(null, null, limit)} is therefore exactly
     * {@link #recent(int)} and is a supported way to call it, not an accident.
     *
     * Both filters are exact matches, not substring searches. {@code action} is drawn from a small
     * set the bot writes and {@code subject} is a Discord snowflake; neither is something a person
     * usefully types half of, and an index answers an equality where it cannot answer a
     * {@code LIKE '%...%'}.
     *
     * @param action  the action to show, or {@code null}/blank for any. Blank counts as "any"
     *                because an empty search box and an absent one are the same intention, and no
     *                row has a blank action to match anyway
     * @param subject the Discord id to show entries about, or {@code null}/blank for any. Entries
     *                with no subject at all are therefore only reachable through an unfiltered call
     * @param limit   how many, at most; a number below 1 is clamped to 1
     * @return the entries, newest first, possibly empty
     */
    List<AuditEntry> search(@Nullable String action, @Nullable String subject, int limit);

    /**
     * Writes one line into the journal.
     *
     * Whoever performs an action records it - there is no central recorder watching the tables,
     * and there should not be one: a trigger could say <em>that</em> a grant appeared but never
     * <em>who asked for it through which surface</em>, which is the whole question the journal is
     * read to answer.
     *
     * It is deliberately a separate statement from the action it describes rather than part of
     * its transaction. A grant that lands without its journal line is a bug worth finding; a member
     * locked out because the bookkeeping failed is worse than the bug.
     *
     * @param action  a short constant, upper case, e.g. {@code GRANT_ACCESS}. Unconstrained by the
     *                schema on purpose - a journal that refuses a value it does not recognise
     *                cannot draw the page it is on
     * @param actor   who did it, as something a person reads. May be null for the system itself
     * @param subject whom it was about - a Discord id where there is one. May be null
     * @param mcUuid  the Minecraft account it concerned, where there is one. May be null
     * @param detail  one line of what happened. May be null
     */
    void record(
            String action,
            @Nullable String actor,
            @Nullable String subject,
            java.util.@Nullable UUID mcUuid,
            @Nullable String detail);
}
