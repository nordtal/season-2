package eu.nordtal.s2.smp;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * The season 2 SMP: Nordtal, the farm world, the Nether and the End, plus milestones, aura,
 * prestige, duels, POIs and graves.
 *
 * <p>Scaffold only — no behaviour yet. The concept is docs/smp.md; see the module section in
 * CLAUDE.md for the build rules that apply to it.
 */
public final class SmpPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("smp enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("smp disabled");
    }
}
