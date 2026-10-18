package eu.nordtal.season.settings;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.season.database.Jdbis;
import java.time.Duration;
import org.jdbi.v3.core.Jdbi;

/** A connection pool and the Jdbi over it. The service closes them on shutdown. */
public record Database(HikariDataSource dataSource, Jdbi jdbi) implements AutoCloseable {

    public static Database open(final DatabaseSpec config, final String name) {
        return over(DatabasePool.open(config, name));
    }

    /** Opens the pool without waiting.
     * A running query is bounded by {@code queryBound} instead of {@code query-timeout-seconds}.
     */
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
