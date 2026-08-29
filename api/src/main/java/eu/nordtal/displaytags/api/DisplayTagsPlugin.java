package eu.nordtal.displaytags.api;

import eu.nordtal.displaytags.api.nametag.NameTagManager;
import org.bukkit.Bukkit;

public interface DisplayTagsPlugin {
    static DisplayTagsPlugin get() {
        return (DisplayTagsPlugin) Bukkit.getPluginManager().getPlugin("DisplayTags");
    }

    NameTagManager getNameTagManager();
}
