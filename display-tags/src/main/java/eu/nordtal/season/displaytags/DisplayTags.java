package eu.nordtal.season.displaytags;

import eu.nordtal.season.displaytags.api.nametag.NameTagManager;
import eu.nordtal.season.displaytags.api.nametag.PlayerNameTag;
import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.displaytags.config.spec.NameTagConfigurationSpec;
import eu.nordtal.season.displaytags.listener.PlayerListener;
import eu.nordtal.season.displaytags.nametag.NameTagManagerImpl;
import eu.nordtal.season.displaytags.nametag.NameTagScheduler;
import eu.nordtal.season.displaytags.nametag.TabUtil;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** Every player's name tag as packet-only text displays, run by the plugin that hosts it. */
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
     * Starts the name tags for {@code host}, from its {@code nametags} group.
     *
     * @throws IllegalArgumentException naming the first value of {@code settings} that cannot be used
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
     * Refuses a {@code nametags} group no name tag can be drawn from.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void check(final NameTagConfigurationSpec settings) {
        new NameTagConfiguration().load(settings);
    }

    /** Takes changed settings: every tag is dropped and drawn again from them. */
    public void reload(final NameTagConfigurationSpec settings) {
        this.nameTagScheduler.end();
        // Drops the old tags instead of only despawning them, clearing entries the new settings might not recreate.
        this.removeAllNameTags();
        this.config.load(settings);
        if (this.config.isEnabled()) {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                this.nameTagManager.createNameTag(player).tick();
            }
        }
        this.nameTagScheduler.start();
    }

    /** Stops the scheduler and removes every tag, restoring the vanilla one for its viewers. */
    public void stop() {
        this.nameTagScheduler.end();
        // A display exists only on the client, so a viewer who never reconnects would keep seeing it.
        this.removeAllNameTags();
        running = null;
    }

    private void removeAllNameTags() {
        for (final PlayerNameTag tag : List.copyOf(this.nameTagManager.getAll())) {
            this.nameTagManager.removeNameTag(tag.getPlayer());
        }
    }

    /** Returns the running instance, for the tags it draws. */
    public static DisplayTags get() {
        return Objects.requireNonNull(running, "DisplayTags has not started");
    }

    public NameTagConfiguration config() {
        return this.config;
    }

    public NameTagManager getNameTagManager() {
        return this.nameTagManager;
    }

    /** Returns the plugin that hosts the name tags, which owns their tasks and listeners. */
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
