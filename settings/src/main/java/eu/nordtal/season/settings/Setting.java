package eu.nordtal.season.settings;

public interface Setting<T> {

    /** Returns the one instance of this group. It reads the values as last taken. */
    T get();

    /**
     * Reads the group again. The values in use stay unchanged when this throws.
     *
     * @throws SettingsException if the group cannot be read or its check refused it
     * @throws IllegalStateException if the group is not {@link Group#whileRunning() taken while the
     *     process runs}
     */
    void reload() throws SettingsException;
}
