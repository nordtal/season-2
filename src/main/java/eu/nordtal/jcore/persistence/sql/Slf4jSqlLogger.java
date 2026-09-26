package eu.nordtal.jcore.persistence.sql;

import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SqlLogger} that writes rendered statements and their execution time to SLF4J.
 *
 * Successful statements are logged at {@code DEBUG}, failures at {@code WARN} (the exception itself
 * still propagates to the caller and is expected to be handled there). Bound parameter values are
 * deliberately <b>not</b> logged, since they routinely contain user data and credentials.
 *
 * Installed by {@link Database} only when {@link DatabaseConfig#logSql()} is {@code true}.
 */
final class Slf4jSqlLogger implements SqlLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(Slf4jSqlLogger.class);

    @Override
    public void logAfterExecution(final StatementContext context) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("SQL [{} ms]: {}", context.getElapsedTime(ChronoUnit.MILLIS), context.getRenderedSql());
        }
    }

    @Override
    public void logException(final StatementContext context, final SQLException exception) {
        LOGGER.warn(
                "SQL failed [{} ms]: {} ({}: {})",
                context.getElapsedTime(ChronoUnit.MILLIS),
                context.getRenderedSql(),
                exception.getSQLState(),
                exception.getMessage());
    }
}
