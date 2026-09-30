package eu.nordtal.s2.settings;

/** One group of settings as last read, and the way to read it again. */
public interface Setting<T> {

    /** Returns the values as last read. */
    T get();

    /**
     * Reads the group again; the values in use stay unchanged when this throws.
     *
     * @throws SettingsException if the group cannot be read or its check refused it
     */
    void reload() throws SettingsException;
}
