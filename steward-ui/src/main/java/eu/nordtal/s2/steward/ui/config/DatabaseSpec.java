package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Name;

/**
 * {@code database.yml} - the same database every other process in this stack reads.
 *
 * <p><b>This process never migrates it.</b> steward-worker owns the schema and is the only thing
 * that applies a migration; the interface reads and writes rows within a schema somebody else put
 * there. If this container starts against an older schema than its jar expects, that is a
 * deployment in the middle of an update, and the answer is to wait for the worker rather than to
 * race it.</p>
 */
@ConfigSpec(header = {
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
