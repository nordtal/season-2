package eu.nordtal.displaytags.util;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.nametag.NameTagManager;
import eu.nordtal.displaytags.DisplayTags;

/**
 * Compatibility layer for the TAB plugin (<a href="https://github.com/NEZNAMY/TAB">NEZNAMY/TAB</a>).
 * <p>
 * TAB manages scoreboard teams itself. When it is present and handling name tags, DisplayTags must
 * not send its own team packets, otherwise both plugins fight over the same teams. Instead, TAB is
 * asked to hide the vanilla name tag for every player it loads.
 * <p>
 * Every call into TAB's API lives in the nested {@link Hook} class so that this class can be loaded
 * and queried on servers where TAB is absent, or where an incompatible TAB version is installed:
 * the linkage error is then confined to {@code Hook} and caught here.
 */
public final class TabUtil {
    private static boolean available;

    private TabUtil() {
    }

    public static void load(DisplayTags plugin) {
        available = false;
        if (!DependencyUtil.enabledTAB()) return;

        try {
            Class.forName("me.neznamy.tab.api.TabAPI", false, TabUtil.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError error) {
            plugin.getLogger().warning("TAB is installed, but its API is not accessible. DisplayTags will manage the vanilla name tags itself.");
            return;
        }

        available = true;

        try {
            Hook.registerNameTagHider(plugin);
            plugin.getLogger().info("TAB is installed on this server. DisplayTags will let TAB hide the vanilla name tags instead of sending its own team packets.");
        } catch (LinkageError | RuntimeException error) {
            available = false;
            plugin.getLogger().warning("Failed to hook into TAB (" + error + "). DisplayTags will manage the vanilla name tags itself.");
        }
    }

    /**
     * Whether TAB is present and currently handling name tags. When this returns {@code true},
     * DisplayTags must leave the scoreboard teams alone.
     */
    public static boolean managesNameTags() {
        if (!available) return false;

        try {
            return Hook.hasNameTagManager();
        } catch (LinkageError | RuntimeException error) {
            available = false;
            return false;
        }
    }

    private static final class Hook {
        static void registerNameTagHider(DisplayTags plugin) {
            // TAB only creates its event bus once it has finished loading, so this can be null.
            EventBus eventBus = TabAPI.getInstance().getEventBus();
            if (eventBus == null) {
                plugin.getLogger().warning("TAB's event bus is not available, so DisplayTags cannot ask TAB to hide the vanilla name tags.");
                return;
            }

            eventBus.register(PlayerLoadEvent.class, (event) -> {
                NameTagManager manager = TabAPI.getInstance().getNameTagManager();
                if (manager == null) return;

                if (plugin.config().nametag().isEnabled()) {
                    manager.hideNameTag(event.getPlayer());
                }
            });
        }

        static boolean hasNameTagManager() {
            TabAPI api = TabAPI.getInstance();
            return api != null && api.getNameTagManager() != null;
        }
    }
}
