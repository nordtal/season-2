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
     * @return this configuration as the record jcore's {@code Database.create} takes, with every
     *         connection field overridable by an environment variable so the password does not
     *         have to sit in the config volume in production
     * @see #POSTGRES_URL_ENV
     */
    public DatabaseConfig toDatabaseConfig() {
        return DatabaseConfig.builder(override(POSTGRES_URL_ENV, jdbcUrl))
                .username(override(POSTGRES_USER_ENV, username))
                .password(override(POSTGRES_PASSWORD_ENV, password))
                .poolName("payments-bot")
                .maximumPoolSize(maximumPoolSize)
                .logSql(logSql)
                .build();
    }

    static final String POSTGRES_URL_ENV = "POSTGRES_URL";
    static final String POSTGRES_USER_ENV = "POSTGRES_USER";
    static final String POSTGRES_PASSWORD_ENV = "POSTGRES_PASSWORD";

    /**
     * Reads {@code variable} from the environment, falling back to the value from the config file.
     * A variable that is set but blank is treated as unset - an empty value in a compose file or
     * orchestrator secret is far more likely to be an accident than an intentional empty password.
     *
     * @param variable  the name of the environment variable to prefer
     * @param fromFile  the value loaded from {@code config/database.json}
     * @return the environment value if one is set and not blank, otherwise {@code fromFile}
     */
    private static String override(final String variable, final String fromFile) {
        final String value = System.getenv(variable);
        return value == null || value.isBlank() ? fromFile : value;
    }
}
