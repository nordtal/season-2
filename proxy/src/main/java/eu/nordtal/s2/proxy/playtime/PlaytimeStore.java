package eu.nordtal.s2.proxy.playtime;

import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/** Where accumulated online time goes; an interface so {@link PlaytimeWriter} tests need no database. */
public interface PlaytimeStore {

    /** A store over the proxy's own pool; it owns nothing and there is nothing to close. */
    static PlaytimeStore using(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        final PlaytimeDao dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(PlaytimeDao.class);
        return dao::add;
    }

    /**
     * Adds a slice of online time to a player's running total, creating the row on first use.
     *
     * @param seconds how many seconds to add, always positive
     */
    void add(String discordId, long seconds);
}
