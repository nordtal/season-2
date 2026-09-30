package eu.nordtal.s2.database.access;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The access schema as a read-only list: everyone, every payment, every grant of one person.
 *
 * Every method is a blocking database round trip, so never call one from a Paper main thread.
 */
public interface RosterDirectory {

    /** Returns a directory over {@code dataSource}. */
    static RosterDirectory using(final DataSource dataSource) {
        return new JdbiRosterDirectory(dataSource);
    }

    /**
     * Returns everyone the bot knows, most recently updated first, including people with no link or grant.
     *
     * @param limit the page size; a number below 1 is clamped to 1
     */
    List<Person> people(int limit);

    /** Returns the row {@link #people(int)} would print for this account, or empty for an unknown id. */
    Optional<Person> personOf(String discordId);

    /**
     * Returns every payment request, newest first.
     *
     * @param limit the page size; a number below 1 is clamped to 1
     */
    List<Payment> payments(int limit);

    /** Returns the payment requests still waiting to be paid, oldest first and unbounded. */
    List<Payment> openPayments();

    /** Returns every access grant of one person, newest first, including expired and revoked ones. */
    List<Grant> grantsOf(String discordId);
}
