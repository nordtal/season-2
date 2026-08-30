package eu.nordtal.s2.accessbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/database.yml}.
 * <p>
 * The hand-written {@code POSTGRES_URL} / {@code POSTGRES_USER} / {@code POSTGRES_PASSWORD}
 * overrides this used to carry are gone: jcore's environment overlay covers every setting, so
 * there is no longer a second, separate way for a value to reach this config.
 * <p>
 * <b>Deploy change:</b> the variables are now {@code NORDTAL_DATABASE_JDBC_URL},
 * {@code NORDTAL_DATABASE_USERNAME} and {@code NORDTAL_DATABASE_PASSWORD}. The old
 * {@code POSTGRES_*} names are no longer read.
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  access-bot - PostgreSQL connection",
        "-------------------------------------------------------------------",
        "In production the password belongs in the environment, not in this",
        "file. Every setting can be overridden with",
        "NORDTAL_DATABASE_<SETTING>:",
        "",
        "  NORDTAL_DATABASE_JDBC_URL",
        "  NORDTAL_DATABASE_USERNAME",
        "  NORDTAL_DATABASE_PASSWORD",
        "",
        "An overridden value is never written back into this file, so a",
        "password handed to the container cannot leak into the config volume.",
        "This file exists so a local checkout runs without setting anything."
})
public interface DatabaseSpec {

    @Order(1)
    @Key("jdbc-url")
    @Comment({
            "JDBC URL of the PostgreSQL database.",
            "The SQL dialect is implied by this URL; jcore names no database itself."
    })
    default String jdbcUrl() {
        return "jdbc:postgresql://localhost:5432/access";
    }

    @Order(2)
    @Key("username")
    @Comment("Database user.")
    default String username() {
        return "access";
    }

    @Order(3)
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_DATABASE_PASSWORD in production.")
    default String password() {
        return "";
    }

    @Order(4)
    @Key("maximum-pool-size")
    @Comment({
            "Upper bound of the HikariCP pool.",
            "The bot's own load is one poll thread plus JDA callbacks, so 10 is generous."
    })
    default int maximumPoolSize() {
        return 10;
    }

    @Order(5)
    @Key("log-sql")
    @Comment({
            "Logs every rendered statement and its duration at DEBUG.",
            "Bound parameter values are never logged."
    })
    default boolean logSql() {
        return false;
    }
}
