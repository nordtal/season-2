package eu.nordtal.s2.smpfarmworld;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Farm world lifecycle and resets on the season 2 SMP.
 *
 * <p>Scaffold only — no behaviour yet. See the module section in CLAUDE.md for what this
 * plugin is meant to own.
 */
public final class SmpFarmWorldPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("smp-farm-world enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("smp-farm-world disabled");
    }
}
