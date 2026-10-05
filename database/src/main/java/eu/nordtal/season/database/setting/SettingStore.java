package eu.nordtal.season.database.setting;

import eu.nordtal.season.common.id.Actor;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Where settings are stored: the groups each process publishes, and only the values an admin changed.
 *
 * A value is JSON text at a dotted path of its group; a list is one value.
 */
public interface SettingStore {

    /** The service whose groups every process loads besides its own. */
    String NETWORK = "network";

    /**
     * One changed value of a group.
     *
     * @param value the value as JSON text
     */
    record Value(String service, String group, String path, String value) {}

    /**
     * A group as its process last published it.
     *
     * @param schema      the spec's schema tree as JSON text
     * @param defaults    what the process runs with where nothing is overridden, as JSON text
     * @param environment the paths the process's environment holds, never their values
     * @param live        whether a change applies without a restart
     * @param problem     why the process refused the stored values, or {@code null} when it took them
     */
    record Group(
            String service,
            String name,
            String schema,
            String defaults,
            List<String> environment,
            boolean live,
            @Nullable String problem,
            Instant published) {

        public Group {
            environment = List.copyOf(environment);
        }
    }

    /** Returns the store in the database behind {@code dataSource}, which the caller owns. */
    static SettingStore using(final DataSource dataSource) {
        return new JdbiSettingStore(dataSource);
    }

    /** Publishes a group's shape and defaults, replacing what the last start published, and clears its problem. */
    void publish(String service, String name, String schema, String defaults, List<String> environment, boolean live);

    /** Records why the process refused its stored values, or clears it with {@code null}. */
    void problem(String service, String name, @Nullable String problem);

    /** Returns every changed value of these services, in one read. */
    List<Value> overrides(Collection<String> services);

    /** Returns every published group, by service and name. */
    List<Group> groups();

    /** Returns one published group. */
    Optional<Group> group(String service, String name);

    /**
     * Sets and removes values of one group, published or not yet, in one transaction if {@code current} holds.
     *
     * @param values  JSON text per path, or {@code null} to remove the path's value
     * @param current asked with the group's values under a lock; {@code false} leaves everything as it was
     * @return whether anything was written, which signals the service
     */
    boolean change(
            String service,
            String name,
            Map<String, @Nullable String> values,
            Actor actor,
            Predicate<List<Value>> current);
}
