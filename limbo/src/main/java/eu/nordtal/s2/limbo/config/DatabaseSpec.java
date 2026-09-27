package eu.nordtal.s2.limbo.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/database.yml}: this plugin's own pool, used only for a player's language at join.
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  limbo: PostgreSQL connection",
            "-------------------------------------------------------------------",
            "In production the password belongs in the environment, not in this",
            "file. Every setting can be overridden with",
            "NORDTAL_LIMBO_DATABASE_<SETTING>:",
            "",
            "  NORDTAL_LIMBO_DATABASE_JDBC_URL",
            "  NORDTAL_LIMBO_DATABASE_USERNAME",
            "  NORDTAL_LIMBO_DATABASE_PASSWORD",
            "",
            "An overridden value is never written back into this file. This is a",
            "SEPARATE connection pool from every other process's own database.yml."
        })
public interface DatabaseSpec {

    @Order(1)
    @Name("JDBC URL")
    @Key("jdbc-url")
    @Comment("JDBC URL of the PostgreSQL database that holds the season 2 schema.")
    @Explain("The full JDBC connection string, including the database name.")
    default String jdbcUrl() {
        return "jdbc:postgresql://localhost:5432/nordtal";
    }

    @Order(2)
    @Name("Username")
    @Key("username")
    @Comment("Database user.")
    @NoExplanationNeeded
    default String username() {
        return "limbo";
    }

    @Order(3)
    @Name("Password")
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_LIMBO_DATABASE_PASSWORD in production.")
    @NoExplanationNeeded
    default String password() {
        return "";
    }

    @Order(4)
    @Name("Connection pool size")
    @Key("maximum-pool-size")
    @Comment({"Upper bound of the HikariCP pool. Small on purpose: one indexed lookup per join, nothing else."})
    @NoExplanationNeeded
    default int maximumPoolSize() {
        return 3;
    }

    @Order(5)
    @Name("Query timeout (seconds)")
    @Key("query-timeout-seconds")
    @Comment({
        "How long this plugin waits for the database before giving up. Applies BOTH to acquiring",
        "a connection and, through the driver's socketTimeout, to a query already running.",
        "Kept short so a struggling database fails fast onto the English fallback."
    })
    @Explain(
            "Limits both waiting for a free connection and a query already running; lower falls back to English faster.")
    default int queryTimeoutSeconds() {
        return 3;
    }
}
