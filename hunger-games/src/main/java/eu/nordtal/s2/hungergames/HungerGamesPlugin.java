package eu.nordtal.s2.hungergames;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * The hunger games start event of season 2.
 *
 * <p>Scaffold only — no behaviour yet. See the module section in CLAUDE.md for what this
 * plugin is meant to own.
 */
public final class HungerGamesPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("hunger-games enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("hunger-games disabled");
    }
}
