package eu.nordtal.s2.database.online;

import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Who is connected right now, one row per player, written by the proxy in the same tick as the counts.
 * Unlike {@link OnlineDirectory#write}, {@link #replace} deletes every row not given.
 */
public interface OnlineRoster {

    /** Returns a roster over a pool the caller owns; there is nothing to close. */
    static OnlineRoster using(final DataSource dataSource, final InstantSource clock) {
        return new JdbiRoster(dataSource, clock);
    }

    /**
     * Makes the table hold exactly these players, stamped with the time of the call.
     *
     * @param connected everyone the proxy currently has; an empty collection empties the table
     * @throws NullPointerException     if the collection or an element is {@code null}
     * @throws IllegalArgumentException if two presences carry the same {@link Presence#uuid()}
     */
    void replace(Collection<Presence> connected);

    /** Returns every player proxy last wrote down, in no order; an empty list may also be stale. */
    List<OnlinePlayer> current();

    /**
     * One connected player as the writer sees them, without a timestamp.
     *
     * @param subject the backend they are on, or {@code null} when no server has them yet; never a guess
     */
    record Presence(UUID uuid, String name, @Nullable String subject) {

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
