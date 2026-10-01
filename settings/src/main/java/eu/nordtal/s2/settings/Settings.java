package eu.nordtal.s2.settings;

/**
 * Where a process's settings come from: one group per spec, each checked once it is read.
 *
 * A process holds this interface, so where the values are kept changes none of its callers.
 */
public interface Settings {

    /**
     * Reads one group of settings.
     *
     * @throws SettingsException if the group cannot be read, or its check refuses even its defaults
     */
    <T> Setting<T> load(Group<T> group) throws SettingsException;
}
