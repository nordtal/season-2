package eu.nordtal.season.displaytags;

import org.bukkit.plugin.PluginManager;

public class DependencyUtil {
    private static boolean enabledTAB;

    public static void load(final DisplayTags plugin) {
        final PluginManager pluginManager = plugin.getServer().getPluginManager();
        enabledTAB = pluginManager.isPluginEnabled("TAB");
    }

    public static boolean enabledTAB() {
        return enabledTAB;
    }
}
