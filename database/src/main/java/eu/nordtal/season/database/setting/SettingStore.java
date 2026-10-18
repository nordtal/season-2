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
 * Stores the groups that each process publishes and the values that an admin changed.
 *
 * A value is JSON text at a dotted path of its group. A list is one value.
 */
public interface SettingStore {

    /** The service whose groups every process loads besides its own. */
    String NETWORK = "network";

    /** One changed value of a group. {@code value} is JSON text. */
    record Value(String service, String group, String path, String value) {}

    /**
     * A group as its process last published it.
     *
     * @param schema the schema tree of the spec, as JSON text
     * @param defaults the values the process runs with where nothing is overridden, as JSON text
     * @param environment the paths that the process's environment holds, never their values
     * @param live whether a change applies without a restart
     * @param problem why the process refused the stored values, null if it took them
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

    /** Returns a store that borrows {@code dataSource}. */
    static SettingStore using(final DataSource dataSource) {
        return new JdbiSettingStore(dataSource);
    }

    /** Publishes the shape and defaults of a group, replaces those of the last start and clears its problem. */
    void publish(String service, String name, String schema, String defaults, List<String> environment, boolean live);

    /** Records why the process refused its stored values. Null clears it. */
    void problem(String service, String name, @Nullable String problem);

    List<Value> overrides(Collection<String> services);

    List<Group> groups();

    Optional<Group> group(String service, String name);

    /**
     * Sets and removes values of one group in one transaction if {@code current} holds. The group may be unpublished.
     *
     * @param values JSON text by path, null removes the value of the path
     * @param current asked with the group's values under a lock. False changes nothing
     * @return whether anything was written, which signals the service
     */
    boolean change(
            String service,
            String name,
            Map<String, @Nullable String> values,
            Actor actor,
            Predicate<List<Value>> current);
}
