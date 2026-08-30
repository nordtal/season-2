package eu.nordtal.displaytags.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.config.spec.DisplayTagsConfigurationSpec;

import java.io.File;

/**
 * Owns {@code plugins/DisplayTags/config.yml}.
 * <p>
 * Up to 2.0.0 this called {@code io.github.revxrsal:spec:1.5} directly and had to know two of
 * its sharp edges itself: the Gson number policy (without which whole numbers were written back
 * as {@code 1.0}) and the fact that a mistyped key was deleted from the file on the next save.
 * Both are handled by jcore's hardened copy now, so neither this class nor any other caller has
 * to know about them.
 */
public class DisplayTagsConfiguration {

    private static final String FILE_NAME = "config.yml";

    private final ConfigHandle<DisplayTagsConfigurationSpec> handle;
    private final NameTagConfiguration nameTagConfig;

    /**
     * @throws ConfigException if the file cannot be read or written, contains a setting that does
     *                         not exist, or holds a value this plugin cannot use. The caller is
     *                         expected to disable the plugin - the server itself keeps running.
     */
    public DisplayTagsConfiguration(DisplayTags plugin) throws ConfigException {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        this.nameTagConfig = new NameTagConfiguration();

        // The validator both checks and applies. NameTagConfiguration#load parses every value
        // that can fail before it assigns anything, so a rejected reload leaves the previously
        // loaded settings intact - and jcore only publishes the new values once the validator
        // has accepted them.
        this.handle = ConfigLoader.builder(file, DisplayTagsConfigurationSpec.class)
                .envPrefix("NORDTAL_DISPLAYTAGS")
                .validator(this.nameTagConfig::load)
                .load();
    }

    /**
     * Re-reads the file. Applies the same strictness as startup: an unknown key or an unusable
     * value is refused and the settings already in effect are kept.
     *
     * @throws IllegalArgumentException with a message written for whoever has to fix the file
     */
    public void reload() {
        try {
            this.handle.reload();
        } catch (ConfigException error) {
            // Deliberately an IllegalArgumentException carrying only the message: the caller
            // (DisplayTags#reloadPlugin) prints it to the console for an operator, and jcore's
            // message already names the file, the setting and what is wrong with it.
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

    public NameTagConfiguration nametag() {
        return this.nameTagConfig;
    }
}
