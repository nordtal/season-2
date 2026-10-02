package eu.nordtal.s2.settings;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;

/** The {@code database} group: the one PostgreSQL connection pool of a process. */
@ConfigSpec
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
        return "nordtal";
    }

    @Order(3)
    @Name("Password")
    @Key("password")
    @Comment("Database password. Prefer the environment in production.")
    @Secret
    @NoExplanationNeeded
    default String password() {
        return "";
    }

    @Order(4)
    @Name("Connection pool size")
    @Key("maximum-pool-size")
    @Comment("Upper bound of the connection pool.")
    @NoExplanationNeeded
    default int maximumPoolSize() {
        return 5;
    }

    @Order(5)
    @Name("Query timeout (seconds)")
    @Key("query-timeout-seconds")
    @Comment({
        "How long this process waits for the database before giving up. Applies BOTH to acquiring",
        "a connection and, through the driver's socketTimeout, to a query already running.",
        "Kept short, so a struggling database fails fast."
    })
    @Explain("Limits both waiting for a free connection and a query already running.")
    default int queryTimeoutSeconds() {
        return 3;
    }
}
