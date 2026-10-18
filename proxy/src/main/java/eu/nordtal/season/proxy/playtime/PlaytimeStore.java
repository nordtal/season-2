package eu.nordtal.season.proxy.playtime;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import java.util.Objects;
import javax.sql.DataSource;

/** Stores the play time. */
public interface PlaytimeStore {

    /** Creates a store that uses the pool of the proxy. */
    static PlaytimeStore using(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        final PlaytimeDao dao = Jdbis.over(dataSource).onDemand(PlaytimeDao.class);
        return dao::add;
    }

    /** Adds the positive number of seconds to the total of the player. It creates the row when it is missing. */
    void add(DiscordId discordId, long seconds);
}
