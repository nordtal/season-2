package eu.nordtal.s2.settings;

/** One group of settings as last taken, and the way to take it again. */
public interface Setting<T> {

    /** Returns the one instance of this group, which always reads the values as last taken: keep it in a field. */
    T get();

    /**
     * Reads the group again; the values in use stay unchanged when this throws.
     *
     * @throws SettingsException if the group cannot be read or its check refused it
     * @throws IllegalStateException if the group is not {@link Group#whileRunning() taken while the process runs}
     */
    void reload() throws SettingsException;
}
