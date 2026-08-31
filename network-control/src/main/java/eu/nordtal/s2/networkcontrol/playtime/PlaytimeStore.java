package eu.nordtal.s2.networkcontrol.playtime;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Where accumulated online time goes. One method, because that is the whole of what the proxy does
 * with {@code player_playtime}: it adds seconds to it.
 * <p>
 * An interface with one implementation, so {@link PlaytimeWriter} - which owns the interesting part,
 * the counting - can be tested without a database while the SQL itself is covered against a real
 * one.
 * </p>
 */
public interface PlaytimeStore {

    /**
     * @param dataSource the proxy's own pool, the same one the access directory borrows
     * @return a store over that pool; it owns nothing and there is nothing to close
     */
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
     * @param discordId the linked Discord account
     * @param seconds   how many seconds to add, always positive
     */
    void add(String discordId, long seconds);
}
