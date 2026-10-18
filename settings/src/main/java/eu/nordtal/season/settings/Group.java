package eu.nordtal.season.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One group of settings a process loads.
 *
 * @param name unique within its service, such as {@code distances}
 * @param check refuses an invalid group by throwing {@link IllegalArgumentException}
 * @param live whether the process takes a change while it runs, otherwise at its next start
 * @param defaults the defaults of this process by dotted path, where the spec's own default does
 *     not fit
 */
public record Group<T>(
        String name, Class<T> spec, Check<T> check, boolean live, boolean network, Map<String, Object> defaults) {

    public Group {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(check, "check");
        defaults = Map.copyOf(defaults);
    }

    public static <T> Group<T> of(final String name, final Class<T> spec) {
        return new Group<>(name, spec, Check.none(), false, false, Map.of());
    }

    public Group<T> checkedBy(final Check<T> refusal) {
        return new Group<>(name, spec, refusal, live, network, defaults);
    }

    /** Returns this group taken while the process runs.
     * A change then calls {@link Setting#reload()}.
     */
    public Group<T> whileRunning() {
        return new Group<>(name, spec, check, true, network, defaults);
    }

    public Group<T> networkWide() {
        return new Group<>(name, spec, check, live, true, defaults);
    }

    /** Returns this group with the default {@code value} at {@code path}. Steward shows it. */
    public Group<T> defaulting(final String path, final Object value) {
        final Map<String, Object> more = new LinkedHashMap<>(defaults);
        more.put(path, value);
        return new Group<>(name, spec, check, live, network, more);
    }
}
