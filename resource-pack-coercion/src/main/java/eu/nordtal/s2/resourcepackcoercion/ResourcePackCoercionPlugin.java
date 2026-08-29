package eu.nordtal.s2.resourcepackcoercion;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies and enforces the nordtal resource pack on the pack-install server.
 *
 * <p>Scaffold only — no behaviour yet. See the module section in CLAUDE.md for what this
 * plugin is meant to own.
 */
public final class ResourcePackCoercionPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("resource-pack-coercion enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("resource-pack-coercion disabled");
    }
}
