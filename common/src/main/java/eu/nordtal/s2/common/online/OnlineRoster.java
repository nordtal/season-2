package eu.nordtal.s2.common.online;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Who is connected right now - written by network-control in the same tick as {@link
 * OnlineDirectory}, read by steward-worker for {@code /api/services} (steward/111, feeding
 * steward/64's avatar row).
 *
 * <h2>Why a second directory next to {@link OnlineDirectory} and not a method on it</h2>
 * {@code online_count} is one row per subject; this is one row per player. They are written from the
 * same pass over the proxy and on the same schedule, and that is the whole of what they share - the
 * shapes, the keys and the deletion rule are different, and folding both into one interface would
 * give it two unrelated halves and a {@code write} that means two things. {@code OnlineWriter} holds
 * them together where that actually matters: one tick, one set of players, both tables.
 *
 * <h2>{@link #replace} replaces the world, and that is not {@link OnlineDirectory#write}'s bargain</h2>
 * A subject missing from {@code OnlineDirectory.write} keeps its row, because a backend the proxy
 * cannot currently see is not a backend with nobody on it. A player missing from {@link #replace}
 * is the opposite: they logged off, and a roster that kept them would be a roster that lies for as
 * long as the staleness window lasts. So the set given is the set that exists afterwards - everyone
 * else is deleted, in the same transaction, and an empty collection empties the table. Nobody
 * online is the normal case on a dev host and it is a legitimate write, not a no-op.
 *
 * <h2>"No list" is not "nobody" - and this interface does not decide that either</h2>
 * {@link #current()} returns the rows as they stand, {@code updated} and all. An empty answer is
 * "nobody was connected when network-control last wrote" exactly as often as it is "network-control
 * has not written in a long time", and telling those apart is
 * {@code eu.nordtal.s2.steward.worker.api.ServicesApi}'s job against its own cutoff - the same split
 * {@link OnlineDirectory} draws for the counts, for the same reason: the table states what was
 * found, one reader decides how old is too old to believe.
 */
public interface OnlineRoster {

    /**
     * @param dataSource the pool the caller already owns - network-control's, or steward-worker's
     * @return a roster over that pool; it owns nothing and there is nothing to close
     */
    static OnlineRoster using(final DataSource dataSource) {
        return new JdbiRoster(dataSource);
    }

    /**
     * Makes the table say exactly this and nothing else: every presence given is written with the
     * time of the call, and every row not named is deleted.
     *
     * <p>Idempotent, like every write in this package: the same set written twice leaves the same
     * rows with a newer {@code updated}, and there is no history to disturb.
     *
     * @param connected everyone the proxy currently has; an empty collection is allowed and empties
     *                  the table, which is what a quiet night looks like
     * @throws NullPointerException     if the collection or an element is {@code null}
     * @throws IllegalArgumentException if two presences carry the same {@link Presence#uuid()} - a
     *                                  player is connected once or not at all, and a duplicate is a
     *                                  bug in the caller rather than a row to pick a winner for
     */
    void replace(Collection<Presence> connected);

    /**
     * @return every player network-control last wrote down, in no particular order - empty when
     *         nobody was connected, and empty when nobody has written in a week. See the class
     *         documentation for why this interface does not tell those two apart
     */
    List<OnlinePlayer> current();

    /**
     * One connected player as the writer sees them, without a timestamp - {@link #replace} stamps
     * every row of one call with the same instant, so that one write is one moment.
     *
     * @param uuid    the Minecraft account
     * @param name    the name on the connection
     * @param subject the backend they are on, or {@code null} when the proxy has them and no server
     *                does yet. Never a guess - see {@code V24__online_player.sql}
     */
    record Presence(UUID uuid, String name, String subject) {

        public Presence {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank for " + uuid);
            }
            if (subject != null && subject.isBlank()) {
                throw new IllegalArgumentException(
                        "subject must be a service name or null, was blank for " + uuid);
            }
        }
    }
}
