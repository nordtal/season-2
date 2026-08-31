package eu.nordtal.s2.limbo;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies and enforces the nordtal resource pack on the pack-install server.
 *
 * <p>Scaffold only — no behaviour yet. See the module section in CLAUDE.md for what this
 * plugin is meant to own.
 */
public final class LimboPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("limbo enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("limbo disabled");
    }
}
