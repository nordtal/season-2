package eu.nordtal.season.settings;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.season.database.Jdbis;
import java.time.Duration;
import org.jdbi.v3.core.Jdbi;

/**
 * A JVM service's connection pool and the Jdbi over it, which the service closes on shutdown.
 *
 * @param dataSource the pool, from {@link DatabasePool#open}
 * @param jdbi       season 2's Jdbi over it
 */
public record Database(HikariDataSource dataSource, Jdbi jdbi) implements AutoCloseable {

    /** Opens the pool named {@code name}, without waiting. */
    public static Database open(final DatabaseSpec config, final String name) {
        return over(DatabasePool.open(config, name));
    }

    /** The same, with a running query bounded by {@code queryBound} instead of {@code query-timeout-seconds}. */
    public static Database open(final DatabaseSpec config, final String name, final Duration queryBound) {
        return over(DatabasePool.open(config, name, queryBound));
    }

    private static Database over(final HikariDataSource pool) {
        return new Database(pool, Jdbis.over(pool));
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
