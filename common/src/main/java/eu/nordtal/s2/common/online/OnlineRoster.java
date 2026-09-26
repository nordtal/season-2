package eu.nordtal.s2.common.online;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Who is connected right now, one row per player, written by the proxy in the same tick as the counts.
 *
 * Unlike {@link OnlineDirectory#write}, {@link #replace} deletes every row not given. An empty answer from
 * {@link #current()} may be stale; judging that is the reader's job.
 */
public interface OnlineRoster {

    /**
     * @param dataSource the pool the caller already owns - proxy's, or steward-worker's
     * @return a roster over that pool; it owns nothing and there is nothing to close
     */
    static OnlineRoster using(final DataSource dataSource) {
        return new JdbiRoster(dataSource);
    }

    /**
     * Makes the table hold exactly these players, stamped with the time of the call.
     *
     * @param connected everyone the proxy currently has; an empty collection empties the table
     * @throws NullPointerException     if the collection or an element is {@code null}
     * @throws IllegalArgumentException if two presences carry the same {@link Presence#uuid()}
     */
    void replace(Collection<Presence> connected);

    /**
     * @return every player proxy last wrote down, in no particular order - empty when
     *         nobody was connected, and empty when nobody has written in a week. See the class
     *         documentation for why this interface does not tell those two apart
     */
    List<OnlinePlayer> current();

    /**
     * One connected player as the writer sees them, without a timestamp.
     *
     * @param subject the backend they are on, or {@code null} when no server has them yet; never a guess
     */
    record Presence(UUID uuid, String name, String subject) {

        public Presence {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank for " + uuid);
            }
            if (subject != null && subject.isBlank()) {
                throw new IllegalArgumentException("subject must be a service name or null, was blank for " + uuid);
            }
        }
    }
}
