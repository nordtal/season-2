package xyz.growaction.javacore.config;

/**
 * This class is currently just a reservation for possible future features.
 * For now, all functionality regarding json config files is handled by {@link JsonConfigLoader}
 *
 * @see JsonConfigLoader
 */
public abstract class JsonConfig {

    /**
     * No-args-constructor
     */
    public JsonConfig() {}

    /**
     * Method that is run before the config is saved
     */
    protected void preSave() {}

    /**
     * Method that is run after the config is loaded
     */
    protected void postLoad() {}

}
