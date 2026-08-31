package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/database.yml} - this plugin's own connection to the shared PostgreSQL database.
 * <p>
 * A separate pool from every other process's - the bot's, the proxy's - even though all of them
 * eventually point at the same instance; see {@code network-control}'s {@code DatabaseSpec} for
 * the same reasoning. This plugin never migrates anything: the schema (including every {@code smp_} table,
 * and {@code hg_game} / {@code hg_member}, which it reads to find the start event's winner) is
 * owned and applied by
 * {@code discord-bot} (docs/architecture.md#schema-ownership); this pool only ever reads and
 * writes rows in tables that already exist.
 * </p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  smp - PostgreSQL connection",
        "-------------------------------------------------------------------",
        "In production the password belongs in the environment, not in this",
        "file. Every setting can be overridden with",
        "NORDTAL_SMP_DATABASE_<SETTING>:",
        "",
        "  NORDTAL_SMP_DATABASE_JDBC_URL",
        "  NORDTAL_SMP_DATABASE_USERNAME",
        "  NORDTAL_SMP_DATABASE_PASSWORD",
        "",
        "An overridden value is never written back into this file. This is a",
        "SEPARATE connection pool from every other process's own database.yml."
})
public interface DatabaseSpec {

    @Order(1)
    @Key("jdbc-url")
    @Comment("JDBC URL of the PostgreSQL database that holds the hunger games schema.")
    default String jdbcUrl() {
        return "jdbc:postgresql://localhost:5432/nordtal";
    }

    @Order(2)
    @Key("username")
    @Comment("Database user.")
    default String username() {
        return "smp";
    }

    @Order(3)
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_SMP_DATABASE_PASSWORD in production.")
    default String password() {
        return "";
    }

    @Order(4)
    @Key("maximum-pool-size")
    @Comment("Upper bound of the HikariCP pool.")
    default int maximumPoolSize() {
        return 5;
    }

    @Order(5)
    @Key("query-timeout-seconds")
    @Comment({
            "How long this plugin waits for the database before giving up - applied BOTH to",
            "acquiring a connection from the pool and, through the PostgreSQL driver's own",
            "socketTimeout, to a query that is already running. Without the second one a database",
            "that accepts a connection and then hangs is not caught by the first at all.",
            "",
            "The language lookup at join is made off",
            "the main thread; the game's own reads and writes are not. 'Off the main thread' bounds",
            "where the wait happens, not how long it lasts. Three seconds is the same value",
            "network-control uses on the login path, and for the same reason: a struggling",
            "database should fail fast rather than queue joins behind itself."
    })
    default int queryTimeoutSeconds() {
        return 3;
    }
}
