package eu.nordtal.season.proxy.playtime;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import java.util.Objects;
import javax.sql.DataSource;

/** Where accumulated online time goes; an interface so {@link PlaytimeWriter} tests need no database. */
public interface PlaytimeStore {

    /** A store over the proxy's own pool; it owns nothing and there is nothing to close. */
    static PlaytimeStore using(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        final PlaytimeDao dao = Jdbis.over(dataSource).onDemand(PlaytimeDao.class);
        return dao::add;
    }

    /**
     * Adds a slice of online time to a player's running total, creating the row on first use.
     *
     * @param seconds how many seconds to add, always positive
     */
    void add(DiscordId discordId, long seconds);
}
