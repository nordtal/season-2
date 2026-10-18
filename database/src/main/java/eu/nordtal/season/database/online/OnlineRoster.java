package eu.nordtal.season.database.online;

import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Stores who is connected, one row per player. The proxy writes it with the counts.
 * Unlike {@link OnlineDirectory#write}, {@link #replace} deletes every row not given.
 */
public interface OnlineRoster {

    /** Returns a roster that borrows {@code dataSource}. */
    static OnlineRoster using(final DataSource dataSource, final InstantSource clock) {
        return new JdbiRoster(dataSource, clock);
    }

    /**
     * Makes the table hold exactly these players, stamped with the time of the call.
     *
     * @param connected everyone connected. An empty collection empties the table
     * @throws NullPointerException if the collection or an element is null
     * @throws IllegalArgumentException if two presences have the same {@link Presence#uuid()}
     */
    void replace(Collection<Presence> connected);

    /** Returns every player that the proxy last wrote, in no order. */
    List<OnlinePlayer> current();

    /**
     * One connected player without a timestamp.
     *
     * @param subject the backend of the player, null when none has them
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
