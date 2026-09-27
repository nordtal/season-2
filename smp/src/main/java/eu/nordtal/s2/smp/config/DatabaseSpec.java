package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** {@code config/database.yml}: this plugin's own pool to the shared PostgreSQL database, which it never migrates. */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  smp: PostgreSQL connection",
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
    @Name("JDBC URL")
    @Key("jdbc-url")
    @Comment("JDBC URL of the PostgreSQL database that holds the smp schema.")
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
        return "smp";
    }

    @Order(3)
    @Name("Password")
    @Key("password")
    @Comment("Database password. Prefer NORDTAL_SMP_DATABASE_PASSWORD in production.")
    @NoExplanationNeeded
    default String password() {
        return "";
    }

    @Order(4)
    @Name("Connection pool size")
    @Key("maximum-pool-size")
    @Comment("Upper bound of the HikariCP pool.")
    @NoExplanationNeeded
    default int maximumPoolSize() {
        return 5;
    }

    @Order(5)
    @Name("Query timeout (seconds)")
    @Key("query-timeout-seconds")
    @Comment({
        "How long this plugin waits for the database before giving up. Applies BOTH to acquiring",
        "a connection and, through the driver's socketTimeout, to a query already running.",
        "Kept short so a struggling database fails fast rather than queueing joins behind itself."
    })
    @Explain("Limits both waiting for a free connection and a query already running, not only the query.")
    default int queryTimeoutSeconds() {
        return 3;
    }
}
