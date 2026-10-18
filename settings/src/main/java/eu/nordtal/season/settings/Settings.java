package eu.nordtal.season.settings;

/**
 * Supplies the settings of a process, one group per spec. Each group is checked once it is read.
 */
public interface Settings {

    /**
     * @throws SettingsException if the group cannot be read, or its check refuses even its defaults
     */
    <T> Setting<T> load(Group<T> group) throws SettingsException;
}
