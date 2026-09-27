package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** {@code database.yml}: the shared database, which this process never migrates. */
@ConfigSpec(
        header = {
            "How the interface reaches PostgreSQL.",
            "The password comes from NORDTAL_STEWARD_UI_DATABASE_PASSWORD and is never written here."
        })
public interface DatabaseSpec {

    @Order(1)
    @Name("JDBC URL")
    @Key("jdbc-url")
    @Comment("The compose service name, since localhost inside a container is the container itself.")
    @Explain("The JDBC connection string, naming the compose service rather than localhost.")
    default String jdbcUrl() {
        return "jdbc:postgresql://postgres:5432/nordtal";
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
    @Comment("From the environment; an empty one refuses to start.")
    @Explain("Set through the environment; an empty value refuses to start.")
    default String password() {
        return "";
    }

    @Order(4)
    @Name("Connection pool size")
    @Key("maximum-pool-size")
    @Comment("Small on purpose: every page is one or two short reads, and a large pool starves the other processes.")
    @NoExplanationNeeded
    default int maximumPoolSize() {
        return 4;
    }
}
