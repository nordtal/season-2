package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/database.yml} - the proxy's own connection to the access database.
 * <p>
 * This is a second, independent connection pool from the bot's - a different process, a different
 * container, its own credentials - even though both eventually point at the same PostgreSQL
 * instance. Nothing here is shared with {@code access-bot/config/database.yml}: the two modules
 * ship in different images and read different config volumes.
 * </p>
 * <p>
 * The proxy is small on purpose ({@code common/build.gradle.kts}: "the proxy's login path is the
 * only hot caller and it is one query"), so this pool stays small too. {@link #queryTimeoutSeconds()}
 * is what makes the login gate's "one query and a short timeout" (docs/access-system.md) an actual
 * property of the connection rather than a hope: it bounds both HikariCP's connection acquisition
 * and, through the PostgreSQL driver's {@code socketTimeout}, a query that is already running. A
 * login must not queue behind a struggling database - it has to fail fast onto the fallback cache
 * instead.
 * </p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  network-control - PostgreSQL connection",
        "-------------------------------------------------------------------",
        "In production the password belongs in the environment, not in this",
        "file. Every setting can be overridden with",
        "NORDTAL_NETWORK_CONTROL_DATABASE_<SETTING>:",
        "",
        "  NORDTAL_NETWORK_CONTROL_DATABASE_JDBC_URL",
        "  NORDTAL_NETWORK_CONTROL_DATABASE_USERNAME",
        "  NORDTAL_NETWORK_CONTROL_DATABASE_PASSWORD",
        "",
        "An overridden value is never written back into this file. This is a",
        "SEPARATE connection pool from access-bot's own config/database.yml -",
        "different process, different container, its own credentials, even",
        "though both usually point at the same PostgreSQL instance."
})
public interface DatabaseSpec {

    @Order(1)
    @Key("jdbc-url")
    @Comment("JDBC URL of the PostgreSQL database that holds the access schema.")
    default String jdbcUrl() {
        return "jdbc:postgresql://localhost:5432/nordtal";
    }

    @Order(2)
    @Key("username")
    @Comment("Database user. Read-mostly: the login path only ever reads, links and issues codes.")
    default String username() {
        return "nordtal";
    }

    @Order(3)
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_NETWORK_CONTROL_DATABASE_PASSWORD in production.")
    default String password() {
        return "";
    }

    @Order(4)
    @Key("maximum-pool-size")
    @Comment({
            "Upper bound of the HikariCP pool.",
            "The login path is one query per join attempt; this does not need to be large."
    })
    default int maximumPoolSize() {
        return 5;
    }

    @Order(5)
    @Key("query-timeout-seconds")
    @Comment({
            "Bounds both connection acquisition and the query itself. A login attempt must not",
            "wait long on a struggling database before the login gate falls back to the",
            "short-lived in-memory cache - see gate.yml's fallback-cache-window-minutes."
    })
    default int queryTimeoutSeconds() {
        return 3;
    }
}
