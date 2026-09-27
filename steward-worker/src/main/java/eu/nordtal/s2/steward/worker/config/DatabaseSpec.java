package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** {@code config/database.yml}: the connection steward-worker migrates through, the only process that does. */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  steward-worker: PostgreSQL connection",
            "-------------------------------------------------------------------",
            "This is the only process that applies the schema, through",
            "`steward-worker migrate` and before `steward-worker apply`.",
            "",
            "Every setting can be overridden with NORDTAL_STEWARD_DATABASE_<SETTING>,",
            "which is where the password belongs in production:",
            "",
            "  NORDTAL_STEWARD_DATABASE_JDBC_URL",
            "  NORDTAL_STEWARD_DATABASE_USERNAME",
            "  NORDTAL_STEWARD_DATABASE_PASSWORD",
            "",
            "An overridden value is never written back into this file."
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
    @Comment("Database user. It needs rights to create and alter tables.")
    @Explain("Needs rights to create and alter tables; every other module's user only reads and writes rows.")
    default String username() {
        return "nordtal";
    }

    @Order(3)
    @Name("Password")
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_STEWARD_DATABASE_PASSWORD in production.")
    @NoExplanationNeeded
    default String password() {
        return "";
    }

    @Order(4)
    @Name("Connection pool size")
    @Key("maximum-pool-size")
    @Comment({
        "Upper bound of the HikariCP pool. `serve` needs three at once: the advisory lock,",
        "the request queries and a spare. The LISTEN connection is outside the pool."
    })
    @Explain(
            "Lower than 4 risks a deadlock: serve needs the advisory lock, the request query and a spare connection at once.")
    default int maximumPoolSize() {
        return 4;
    }

    @Order(5)
    @Name("Query timeout (seconds)")
    @Key("query-timeout-seconds")
    @Comment({
        "Bounds connection acquisition and the statements themselves. Far larger than",
        "elsewhere, since a migration killed half way through is worse than a slow one."
    })
    @Explain(
            "Deliberately far larger than any other module's, since a migration killed by a timeout is worse than a slow one.")
    default int queryTimeoutSeconds() {
        return 300;
    }
}
