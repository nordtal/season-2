package eu.nordtal.s2.limbo.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/database.yml} - this plugin's own connection to the shared PostgreSQL database.
 * <p>
 * A separate pool from every other process's, even though all of them point at the same instance.
 * This plugin never migrates anything and never writes at all: the schema is owned by
 * {@code discord-bot}, and the only query the waiting room makes is the one behind
 * {@code PlayerLocales} - a player's language, read once at join.
 * </p>
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  limbo - PostgreSQL connection",
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
    @Comment({
        "Upper bound of the HikariCP pool. Smaller than the other modules' on purpose: this",
        "one makes a single indexed lookup per join and nothing else, ever."
    })
    @NoExplanationNeeded
    default int maximumPoolSize() {
        return 3;
    }

    @Order(5)
    @Name("Query timeout (seconds)")
    @Key("query-timeout-seconds")
    @Comment({
        "How long this plugin waits for the database before giving up - applied BOTH to",
        "acquiring a connection from the pool and, through the PostgreSQL driver's own",
        "socketTimeout, to a query that is already running. Without the second one a database",
        "that accepts a connection and then hangs is not caught by the first at all.",
        "",
        "The one query this plugin makes is off the main thread, but that bounds where the",
        "wait happens, not how long it lasts: a struggling database should fail fast onto the",
        "English fallback rather than queue joins behind itself."
    })
    @Explain(
            "Limits both waiting for a free connection and a query already running - a low value falls back to the English messages faster.")
    default int queryTimeoutSeconds() {
        return 3;
    }
}
