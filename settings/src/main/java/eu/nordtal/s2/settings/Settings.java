package eu.nordtal.s2.settings;

/**
 * Where a process's settings come from: one named group per spec, each checked once it is read.
 *
 * Files are the only source; a process holds this interface, so a new source changes none of its callers.
 */
public interface Settings {

    /**
     * Reads one group of settings described by {@code spec}, and refuses it when {@code check} throws.
     *
     * @param name  the group's name, which a file source turns into {@code <name>.yml}
     * @param spec  the {@code @ConfigSpec} interface describing every value
     * @param check what a valid group is beyond its types; it throws {@link IllegalArgumentException}
     * @throws SettingsException if the group cannot be read or {@code check} refused it
     */
    <T> Setting<T> load(String name, Class<T> spec, Check<T> check) throws SettingsException;

    /** Reads one group of settings that needs no check beyond its types. */
    default <T> Setting<T> load(final String name, final Class<T> spec) throws SettingsException {
        return load(name, spec, Check.none());
    }
}
