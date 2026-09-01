package eu.nordtal.s2.updater.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/database.yml} - the connection the updater applies the schema through.
 *
 * <h2>This is the process that migrates, and it is the only one</h2>
 * From 2026-09-01 the Flyway call lives here rather than in {@code discord-bot}. The SQL itself did
 * not move: it stays in {@code common/src/main/resources/db/migration/}, next to the API that reads
 * it, and arrives on this module's classpath because {@code :common} is shaded into its jar - the
 * same way it arrived on the bot's.
 *
 * <p>Why it moved: a release that adds a table is a release that adds a migration, so the schema
 * and the versions are one thing and belong to one owner. Until then the coupling was held by an
 * operator rule written in prose - "bring the bot up first, it is the only process that migrates" -
 * which is a rule that works right up to the deployment where somebody does it in the other order.
 * </p>
 *
 * <p><b>The consequence is deliberate: without this container there is no schema.</b> A first
 * deployment runs the updater before the bot and before any server.</p>
 *
 * <h2>A small pool with a long patience</h2>
 * The one-shot commands open a connection, do one thing and exit; {@code updater serve} holds the
 * pool for as long as it runs, and takes one connection out of it for the whole of an apply to hold
 * the advisory lock. Either way there is very little concurrency to size for, which is why the pool
 * is small - and why the timeout is much larger than anywhere else: a migration on a table with a
 * season's worth of playtime rows in it is allowed to take minutes, and a login is not.
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  updater - PostgreSQL connection",
        "-------------------------------------------------------------------",
        "THIS IS THE ONLY PROCESS THAT APPLIES THE SCHEMA. The migrations",
        "live in common/src/main/resources/db/migration and are applied from",
        "this container - by `updater migrate`, and by `updater apply`",
        "before it moves a single jar.",
        "",
        "In production the password belongs in the environment, not in this",
        "file. Every setting can be overridden with",
        "NORDTAL_UPDATER_DATABASE_<SETTING>:",
        "",
        "  NORDTAL_UPDATER_DATABASE_JDBC_URL",
        "  NORDTAL_UPDATER_DATABASE_USERNAME",
        "  NORDTAL_UPDATER_DATABASE_PASSWORD",
        "",
        "An overridden value is never written back into this file."
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
    @Comment({
            "Database user. This one needs more rights than any other module's: it creates and",
            "alters tables. Every other process in this deployment only reads and writes rows."
    })
    default String username() {
        return "nordtal";
    }

    @Order(3)
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_UPDATER_DATABASE_PASSWORD in production.")
    default String password() {
        return "";
    }

    @Order(4)
    @Key("maximum-pool-size")
    @Comment({
            "Upper bound of the HikariCP pool.",
            "",
            "Four, and the number is not arbitrary. `updater migrate` and `updater apply` need one",
            "connection and exit. `updater serve` needs three at once in the worst case: one held",
            "for the whole of an apply by the advisory lock that stops two updaters moving jars at",
            "the same time, one for the queries that claim and finish the request, and one spare so",
            "that a slow query cannot deadlock the other two. The LISTEN connection is NOT one of",
            "these - pgjdbc opens it directly, outside the pool, because LISTEN is session state a",
            "pool would hand back out."
    })
    default int maximumPoolSize() {
        return 4;
    }

    @Order(5)
    @Key("query-timeout-seconds")
    @Comment({
            "Bounds connection acquisition and the statements themselves.",
            "",
            "Far larger than any other module's three seconds, deliberately. An index added to a",
            "table with a season's worth of playtime rows in it is allowed to take minutes; a",
            "migration killed half way through by a timeout is the one failure this whole",
            "arrangement exists to avoid."
    })
    default int queryTimeoutSeconds() {
        return 300;
    }
}
