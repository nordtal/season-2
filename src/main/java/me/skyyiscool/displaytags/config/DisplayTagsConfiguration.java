package me.skyyiscool.displaytags.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import me.skyyiscool.displaytags.DisplayTags;
import me.skyyiscool.displaytags.config.spec.DisplayTagsConfigurationSpec;
import revxrsal.spec.CommentedConfiguration;
import revxrsal.spec.SpecAdapterFactory;
import revxrsal.spec.Specs;

import java.io.File;

public class DisplayTagsConfiguration {
    /**
     * The JSON instance Spec serializes configuration values through.
     * <p>
     * Spec round-trips every written value through Gson's generic {@code Object} type, and Gson's
     * default number strategy reads every JSON number back as a {@code Double}. Whole numbers would
     * therefore land in the generated config.yml as {@code update-interval: 1.0}. They are read back
     * correctly, it just looks sloppy in a shipped file - {@link ToNumberPolicy#LONG_OR_DOUBLE}
     * keeps whole numbers whole. Everything else matches Spec's own default instance.
     */
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(SpecAdapterFactory.INSTANCE)
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    private final DisplayTagsConfigurationSpec config;
    private final NameTagConfiguration nameTagConfig;

    public DisplayTagsConfiguration(DisplayTags plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        this.config = Specs.fromConfig(
                DisplayTagsConfigurationSpec.class,
                CommentedConfiguration.from(file.toPath(), GSON)
        );
        this.nameTagConfig = new NameTagConfiguration();

        this.config.save();
        this.load();
    }

    public void load() {
        this.nameTagConfig.load(this.config);
    }

    public void reload() {
        this.config.reload();
        this.load();
    }

    public NameTagConfiguration nametag() {
        return this.nameTagConfig;
    }
}
