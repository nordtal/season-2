package eu.nordtal.season.settings;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.season.database.Jdbis;
import org.jdbi.v3.core.Jdbi;

/**
 * A JVM service's connection pool and the Jdbi over it, which the service closes on shutdown.
 *
 * @param dataSource the pool, from {@link DatabasePool#openUnbounded}
 * @param jdbi       season 2's Jdbi over it
 */
public record Database(HikariDataSource dataSource, Jdbi jdbi) implements AutoCloseable {

    /** Opens the pool named {@code name}, without waiting. */
    public static Database open(final DatabaseSpec config, final String name) {
        final HikariDataSource pool = DatabasePool.openUnbounded(config, name);
        return new Database(pool, Jdbis.over(pool));
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
