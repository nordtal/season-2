package eu.nordtal.displaytags.api;

import eu.nordtal.displaytags.api.nametag.NameTagManager;
import org.bukkit.Bukkit;

/**
 * The entry point other plugins use to reach DisplayTags.
 */
public interface DisplayTagsPlugin {
    /**
     * @return the running DisplayTags plugin instance
     */
    static DisplayTagsPlugin get() {
        return (DisplayTagsPlugin) Bukkit.getPluginManager().getPlugin("DisplayTags");
    }

    /**
     * @return the manager that owns every player's name tag
     */
    NameTagManager getNameTagManager();
}
