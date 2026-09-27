package eu.nordtal.s2.proxy.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/database.yml}: the proxy's own pool on the season 2 database.
 *
 * {@link #queryTimeoutSeconds()} also bounds a running query, so a login fails fast onto the fallback cache.
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  proxy: PostgreSQL connection",
            "-------------------------------------------------------------------",
            "In production the password belongs in the environment, not in this",
            "file. Every setting can be overridden with",
            "NORDTAL_PROXY_DATABASE_<SETTING>:",
            "",
            "  NORDTAL_PROXY_DATABASE_JDBC_URL",
            "  NORDTAL_PROXY_DATABASE_USERNAME",
            "  NORDTAL_PROXY_DATABASE_PASSWORD",
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
    @Comment("Database user. Read-mostly: the login path only ever reads, links and issues codes.")
    @NoExplanationNeeded
    default String username() {
        return "nordtal";
    }

    @Order(3)
    @Name("Password")
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_PROXY_DATABASE_PASSWORD in production.")
    @NoExplanationNeeded
    default String password() {
        return "";
    }

    @Order(4)
    @Name("Connection pool size")
    @Key("maximum-pool-size")
    @Comment({
        "Upper bound of the HikariCP pool.",
        "The login path is one query per join attempt; this does not need to be large."
    })
    @NoExplanationNeeded
    default int maximumPoolSize() {
        return 5;
    }

    @Order(5)
    @Name("Query timeout (seconds)")
    @Key("query-timeout-seconds")
    @Comment({
        "Bounds both connection acquisition and the query itself, so a login falls back",
        "quickly to the in-memory cache (see gate.yml's fallback-cache-window-minutes)."
    })
    @Explain(
            "Limits both waiting for a free connection and the query itself, before the login gate falls back to its cache.")
    default int queryTimeoutSeconds() {
        return 3;
    }
}
