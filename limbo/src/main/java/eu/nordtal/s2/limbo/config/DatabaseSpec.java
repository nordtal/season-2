package eu.nordtal.s2.limbo.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/database.yml} - this plugin's own connection to the shared PostgreSQL database.
 * <p>
 * A separate pool from every other process's - the bot's, the proxy's - even though all of them
 * eventually point at the same instance; see {@code network-control}'s {@code DatabaseSpec} for
 * the same reasoning. This plugin never migrates anything and never writes at all: the schema is owned and
 * applied by {@code discord-bot} (docs/architecture.md#schema-ownership), and the only query the
 * waiting room ever makes is the one behind {@code PlayerLocales} - a player's language, read once
 * at join through {@code account_link} into {@code discord_user.locale}.
 * </p><p>
 * <b>A waiting room with a database looks like over-engineering and is not.</b> Its entire
 * interface is one translated title, and docs/i18n.md settles where a language comes from: the
 * plugin reads it from the database at join, exactly like every other module. The alternative -
 * the proxy sending it in a plugin message - was rejected there, because it introduces a protocol
 * and the question of what is true before the message arrives, on a path where something is always
 * being rendered.
 * </p>
 */
@ConfigSpec(header = {
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
    @Key("jdbc-url")
    @Comment("JDBC URL of the PostgreSQL database that holds the season 2 schema.")
    default String jdbcUrl() {
        return "jdbc:postgresql://localhost:5432/nordtal";
    }

    @Order(2)
    @Key("username")
    @Comment("Database user.")
    default String username() {
        return "limbo";
    }

    @Order(3)
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_LIMBO_DATABASE_PASSWORD in production.")
    default String password() {
        return "";
    }

    @Order(4)
    @Key("maximum-pool-size")
    @Comment({
            "Upper bound of the HikariCP pool. Smaller than the other modules' on purpose: this",
            "one makes a single indexed lookup per join and nothing else, ever."
    })
    default int maximumPoolSize() {
        return 3;
    }

    @Order(5)
    @Key("query-timeout-seconds")
    @Comment({
            "How long this plugin waits for the database before giving up - applied BOTH to",
            "acquiring a connection from the pool and, through the PostgreSQL driver's own",
            "socketTimeout, to a query that is already running. Without the second one a database",
            "that accepts a connection and then hangs is not caught by the first at all.",
            "",
            "The only query this plugin makes is a player's language at join, and it is made off",
            "the main thread - but 'off the main thread' bounds where the wait happens, not how",
            "long it lasts. Three seconds is the same value network-control uses on the login",
            "path, and for the same reason: a struggling database should fail fast onto the",
            "English fallback rather than queue joins behind itself."
    })
    default int queryTimeoutSeconds() {
        return 3;
    }
}
