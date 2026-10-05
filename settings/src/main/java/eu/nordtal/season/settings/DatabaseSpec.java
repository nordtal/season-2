package eu.nordtal.season.settings;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Secret;

/** The {@code database} group: the one PostgreSQL connection pool of a process. */
@ConfigSpec
public interface DatabaseSpec {

    @Order(1)
    @Name("JDBC URL")
    @Key("jdbc-url")
    @Explain("The full JDBC connection string, including the database name.")
    default String jdbcUrl() {
        return "jdbc:postgresql://localhost:5432/nordtal";
    }

    @Order(2)
    @Name("Username")
    @Key("username")
    @NoExplanationNeeded
    default String username() {
        return "nordtal";
    }

    @Order(3)
    @Name("Password")
    @Key("password")
    @Secret
    @NoExplanationNeeded
    default String password() {
        return "";
    }

    @Order(4)
    @Name("Connection pool size")
    @Key("maximum-pool-size")
    @NoExplanationNeeded
    default int maximumPoolSize() {
        return 5;
    }

    @Order(5)
    @Name("Query timeout (seconds)")
    @Key("query-timeout-seconds")
    @Explain("Limits both waiting for a free connection and a query already running.")
    default int queryTimeoutSeconds() {
        return 3;
    }
}
