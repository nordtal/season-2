package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code database.yml} - the same database every other process in this stack reads.
 *
 * This process never migrates it; steward-worker owns the schema and is the only thing that
 * applies a migration.
 */
@ConfigSpec(
        header = {
            "How the interface reaches PostgreSQL.",
            "",
            "The password comes from the environment (NORDTAL_STEWARD_UI_DATABASE_PASSWORD) and is",
            "never written back into this file."
        })
public interface DatabaseSpec {

    @Order(1)
    @Name("JDBC URL")
    @Key("jdbc-url")
    @Comment("The compose service name, not localhost - localhost inside a container is itself.")
    @Explain("The full JDBC connection string. Use the compose service name, not localhost.")
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
    @Comment("From the environment. There is no default, and an empty one refuses to start.")
    @Explain("Set through the environment. An empty value here refuses to start rather than falling back to anything.")
    default String password() {
        return "";
    }

    @Order(4)
    @Name("Connection pool size")
    @Key("maximum-pool-size")
    @Comment({
        "Small on purpose. This is an interface for three admins, and every page it draws is",
        "one or two short reads; a large pool here would only take connections away from the",
        "processes that need them under load."
    })
    @NoExplanationNeeded
    default int maximumPoolSize() {
        return 4;
    }
}
