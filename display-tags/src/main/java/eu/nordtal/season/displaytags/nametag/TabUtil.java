package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.DependencyUtil;
import eu.nordtal.season.displaytags.DisplayTags;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.nametag.NameTagManager;

/**
 * Hides the vanilla name tag through TAB if TAB handles name tags.
 * Every call into TAB is in {@link Hook}, so a missing or incompatible TAB fails there.
 */
public final class TabUtil {
    private static boolean available;

    private TabUtil() {}

    public static void load(final DisplayTags plugin) {
        available = false;
        if (!DependencyUtil.enabledTAB()) return;

        try {
            Class.forName("me.neznamy.tab.api.TabAPI", false, TabUtil.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError error) {
            plugin.getLogger().warning("TAB API not accessible, own name tags used");
            return;
        }

        available = true;

        try {
            Hook.registerNameTagHider(plugin);
            plugin.getLogger().info("TAB found");
        } catch (LinkageError | RuntimeException error) {
            available = false;
            plugin.getLogger().warning("TAB hook failed, own name tags used: cause=" + error);
        }
    }

    /** Whether TAB is present and handles name tags. If so, DisplayTags leaves the scoreboard teams alone. */
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
        static void registerNameTagHider(final DisplayTags plugin) {
            // TAB creates its event bus after it has loaded, so this can be null.
            final EventBus eventBus = TabAPI.getInstance().getEventBus();
            if (eventBus == null) {
                plugin.getLogger().warning("TAB event bus missing, vanilla name tags not hidden by TAB");
                return;
            }

            eventBus.register(PlayerLoadEvent.class, (event) -> {
                final NameTagManager manager = TabAPI.getInstance().getNameTagManager();
                if (manager == null) return;

                manager.hideNameTag(event.getPlayer());
            });
        }

        static boolean hasNameTagManager() {
            final TabAPI api = TabAPI.getInstance();
            return api != null && api.getNameTagManager() != null;
        }
    }
}
