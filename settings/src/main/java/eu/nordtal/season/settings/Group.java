package eu.nordtal.season.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One group of settings a process loads: its name, its spec, what a valid group is and when a change applies.
 *
 * @param name     unique within its service, such as {@code distances}
 * @param spec     the {@code @ConfigSpec} interface describing every value
 * @param check    what a valid group is beyond its types; it throws {@link IllegalArgumentException}
 * @param live     whether the process takes a change while it runs; otherwise at its next start
 * @param network  whether every process shares the group rather than owning it
 * @param defaults what this process runs with where the spec's own default does not fit, by dotted path
 */
public record Group<T>(
        String name, Class<T> spec, Check<T> check, boolean live, boolean network, Map<String, Object> defaults) {

    public Group {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(check, "check");
        defaults = Map.copyOf(defaults);
    }

    /** Returns a group of this process that applies at its next start and needs no check beyond its types. */
    public static <T> Group<T> of(final String name, final Class<T> spec) {
        return new Group<>(name, spec, Check.none(), false, false, Map.of());
    }

    /** Returns this group refused whenever {@code refusal} throws. */
    public Group<T> checkedBy(final Check<T> refusal) {
        return new Group<>(name, spec, refusal, live, network, defaults);
    }

    /** Returns this group taken while the process runs, which then calls {@link Setting#reload()} on a change. */
    public Group<T> whileRunning() {
        return new Group<>(name, spec, check, true, network, defaults);
    }

    /** Returns this group shared by every process, which an admin changes once for the whole network. */
    public Group<T> networkWide() {
        return new Group<>(name, spec, check, live, true, defaults);
    }

    /** Returns this group defaulting to {@code value} at {@code path} in this process, which Steward shows. */
    public Group<T> defaulting(final String path, final Object value) {
        final Map<String, Object> more = new LinkedHashMap<>(defaults);
        more.put(path, value);
        return new Group<>(name, spec, check, live, network, more);
    }
}
