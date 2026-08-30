package eu.nordtal.s2.paymentsbot.config;

import eu.nordtal.jcore.config.JsonConfig;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;

import lombok.Getter;
import lombok.Setter;

/**
 * PostgreSQL connection settings, loaded from {@code config/database.json} the same way
 * {@link PaymentProcessingConfig} is loaded from {@code config/payment-processing.json}.
 * <p>
 * The pool sizing knobs jcore exposes but nobody has ever needed to tune here (idle timeout,
 * max lifetime, connection timeout, pool name) are left at jcore's defaults rather than mirrored
 * into JSON.
 * </p>
 */
@Getter
@Setter
public class BotDatabaseConfig extends JsonConfig {

    /**
     * JDBC URL of the PostgreSQL database. The dialect is implied by this URL - jcore names no
     * database itself.
     */
    private String jdbcUrl = "jdbc:postgresql://localhost:5432/payments";

    private String username = "payments";

    private String password = "";

    /**
     * Upper bound of the HikariCP pool. The bot's own load is one poll thread plus JDA callbacks,
     * so jcore's default of 10 is already generous.
     */
    private int maximumPoolSize = 10;

    /**
     * Logs every rendered statement and its duration at {@code DEBUG}. Bound values are never
     * logged. Off by default.
     */
    private boolean logSql = false;

    /**
     * @return this configuration as the record jcore's {@code Database.create} takes
     */
    public DatabaseConfig toDatabaseConfig() {
        return DatabaseConfig.builder(jdbcUrl)
                .username(username)
                .password(password)
                .poolName("payments-bot")
                .maximumPoolSize(maximumPoolSize)
                .logSql(logSql)
                .build();
    }
}
