package eu.nordtal.season.displaytags;

import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.displaytags.config.spec.NameTagConfigurationSpec;
import eu.nordtal.season.displaytags.nametag.EntityNameTag;
import eu.nordtal.season.displaytags.nametag.NameTagManager;
import eu.nordtal.season.displaytags.nametag.NameTagManagerImpl;
import eu.nordtal.season.displaytags.nametag.NameTagScheduler;
import eu.nordtal.season.displaytags.nametag.PlayerListener;
import eu.nordtal.season.displaytags.nametag.PlayerNameTag;
import eu.nordtal.season.displaytags.nametag.TabUtil;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** The name tag of every player as packet-only text displays. */
public final class DisplayTags {
    private static @Nullable DisplayTags running;

    private final Plugin host;
    private final NameTagConfiguration config = new NameTagConfiguration();
    private final NameTagManager nameTagManager = new NameTagManagerImpl();
    private final NameTagScheduler nameTagScheduler;

    private DisplayTags(final Plugin host) {
        this.host = host;
        this.nameTagScheduler = new NameTagScheduler(this);
    }

    /**
     * Starts the name tags for {@code host} from its {@code nametags} group.
     * Throws {@link IllegalArgumentException} naming the first unusable value.
     */
    public static DisplayTags start(final Plugin host, final NameTagConfigurationSpec settings) {
        final DisplayTags tags = new DisplayTags(host);
        tags.config.load(settings);
        running = tags;
        DependencyUtil.load(tags);
        TabUtil.load(tags);
        tags.nameTagScheduler.start();
        host.getServer().getPluginManager().registerEvents(new PlayerListener(tags), host);
        return tags;
    }

    /**
     * Refuses a {@code nametags} group that cannot draw a name tag.
     * Throws {@link IllegalArgumentException} naming the first wrong value.
     */
    public static void check(final NameTagConfigurationSpec settings) {
        new NameTagConfiguration().load(settings);
    }

    public void reload(final NameTagConfigurationSpec settings) {
        this.nameTagScheduler.end();
        // Dropping the tags clears entries that the new settings may not recreate.
        this.removeAllNameTags();
        this.config.load(settings);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.nameTagManager.createNameTag(player).tick();
        }
        this.nameTagScheduler.start();
    }

    public void stop() {
        this.nameTagScheduler.end();
        // A display exists on the client only, so a viewer who never reconnects would keep seeing it.
        this.removeAllNameTags();
        // A reload keeps them: they read the settings on every tick.
        for (final EntityNameTag tag : List.copyOf(this.nameTagManager.getAllEntityNameTags())) {
            this.nameTagManager.removeEntityNameTag(tag.getEntity());
        }
        running = null;
    }

    private void removeAllNameTags() {
        for (final PlayerNameTag tag : List.copyOf(this.nameTagManager.getAll())) {
            this.nameTagManager.removeNameTag(tag.getPlayer());
        }
    }

    public static DisplayTags get() {
        return Objects.requireNonNull(running, "DisplayTags has not started");
    }

    public NameTagConfiguration config() {
        return this.config;
    }

    public NameTagManager getNameTagManager() {
        return this.nameTagManager;
    }

    public Plugin host() {
        return this.host;
    }

    public Server getServer() {
        return this.host.getServer();
    }

    public Logger getLogger() {
        return this.host.getLogger();
    }
}
