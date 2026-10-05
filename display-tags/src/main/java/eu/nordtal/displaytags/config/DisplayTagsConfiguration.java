package eu.nordtal.displaytags.config;

import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.config.spec.DisplayTagsConfigurationSpec;
import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import java.io.File;

/**
 * Owns {@code plugins/DisplayTags/config.yml}.
 *
 * Validation and defaulting go through jcore's config layer, so this class does not have to guard
 * against a mistyped key being silently deleted or a whole number being rewritten as a float.
 *
 * The YAML jcore writes carries no comments and no header. The first load, reload or save rewrites
 * {@code config.yml} down to bare keys and values — every {@code @Comment} on the spec interfaces in
 * this package is still read, but it no longer reaches the file. It is written instead into
 * {@code config.schema.json} beside the YAML. An operator who opens {@code config.yml} after an
 * update and finds the explanations gone is seeing the intended behaviour, not a bug.
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
    public DisplayTagsConfiguration(final DisplayTags plugin) throws ConfigException {
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        this.nameTagConfig = new NameTagConfiguration();

        // NameTagConfiguration#load parses every value first, so a rejected reload keeps the old settings.
        this.handle = ConfigLoader.builder(file, DisplayTagsConfigurationSpec.class)
                .envPrefix("NORDTAL_DISPLAYTAGS")
                .validator(this.nameTagConfig::load)
                .load();
    }

    /**
     * Re-reads the file.
     *
     * Applies the same strictness as startup: an unknown key or an unusable value is refused and
     * the settings already in effect are kept.
     *
     * @throws IllegalArgumentException with a message written for whoever has to fix the file
     */
    public void reload() {
        try {
            this.handle.reload();
        } catch (ConfigException error) {
            // DisplayTags#reloadPlugin prints this; jcore's message already names the file and the setting.
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

    public NameTagConfiguration nametag() {
        return this.nameTagConfig;
    }
}
