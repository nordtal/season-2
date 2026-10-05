package eu.nordtal.displaytags;

import com.google.common.base.Splitter;
import eu.nordtal.displaytags.api.DisplayTagsPlugin;
import eu.nordtal.displaytags.api.nametag.NameTagManager;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import eu.nordtal.displaytags.commands.DisplayTagsCommand;
import eu.nordtal.displaytags.config.ConfigurationMigrator;
import eu.nordtal.displaytags.config.DisplayTagsConfiguration;
import eu.nordtal.displaytags.listener.PlayerListener;
import eu.nordtal.displaytags.nametag.NameTagManagerImpl;
import eu.nordtal.displaytags.nametag.NameTagScheduler;
import eu.nordtal.displaytags.nametag.TabUtil;
import eu.nordtal.jcore.config.exception.ConfigException;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class DisplayTags extends JavaPlugin implements DisplayTagsPlugin {
    private static @Nullable DisplayTags INSTANCE;

    private @Nullable DisplayTagsConfiguration config;
    private @Nullable NameTagManager nameTagManager;
    private @Nullable NameTagScheduler nameTagScheduler;

    @Override
    public void onLoad() {
        INSTANCE = this;

        try {
            // Runs before the config is read, or jcore overwrites the file with defaults and old settings are lost.
            ConfigurationMigrator.migrate(this);

            this.config = new DisplayTagsConfiguration(this);
        } catch (ConfigException | RuntimeException error) {
            // A bad config value must not crash the server; onEnable disables the plugin instead.
            this.config = null;
            getLogger().severe("Could not read plugins/DisplayTags/config.yml:");
            for (final String line : Splitter.on('\n').split(String.valueOf(error.getMessage()))) {
                getLogger().severe("  " + line);
            }
            return;
        }

        this.nameTagManager = new NameTagManagerImpl();
        this.nameTagScheduler = new NameTagScheduler(this);
    }

    @Override
    public void onEnable() {
        if (this.config == null) {
            getLogger().severe("DisplayTags is not starting up because its configuration could not be read.");
            getLogger()
                    .severe(
                            "Correct the error reported above in plugins/DisplayTags/config.yml, then restart the server.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            DependencyUtil.load(this);

            final PluginManager pluginManager = getServer().getPluginManager();
            final CommandMap commandMap = getServer().getCommandMap();

            TabUtil.load(this);

            this.nameTagScheduler().start();

            pluginManager.registerEvents(new PlayerListener(this), this);
            commandMap.register("displaytags", new DisplayTagsCommand(this));
        } catch (Exception error) {
            getLogger().severe("DisplayTags failed to start up: " + error);
            error.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final String version = getPluginMeta().getVersion();
        getLogger().info(String.format("Enabled DisplayTags v%s.", version));
    }

    @Override
    public void onDisable() {
        // Fields stay null when onLoad or onEnable bailed out early; Bukkit still calls onDisable then.
        if (this.nameTagScheduler != null) {
            this.nameTagScheduler.end();
        }

        // Despawns explicitly: a display exists only on the client, so a viewer who never reconnects keeps seeing it.
        if (this.nameTagManager != null) {
            this.removeAllNameTags();
        }

        getLogger().info("Disabled DisplayTags.");
    }

    /**
     * Removes every registered name tag, restoring the vanilla one for its viewers.
     */
    private void removeAllNameTags() {
        final NameTagManager manager = this.nameTagManager();
        for (final PlayerNameTag tag : List.copyOf(manager.getAll())) {
            manager.removeNameTag(tag.getPlayer());
        }
    }

    public boolean reloadPlugin() {
        getLogger().info("Reloading DisplayTags...");

        this.nameTagScheduler().end();

        // Drops the old tags instead of only despawning them, clearing entries the new config might not recreate.
        this.removeAllNameTags();

        try {
            this.config().reload();
        } catch (IllegalArgumentException error) {
            // A rejected value never reaches the live config, so the plugin carries on with the previous one.
            getLogger().severe("Failed to reload the plugin configuration:");
            getLogger().severe("  " + error.getMessage());
            getLogger().severe("Keeping the configuration that was loaded before.");

            this.startNameTags();
            return false;
        } catch (Exception error) {
            getLogger().severe("Failed to reload the plugin configuration: " + error);
            getLogger().severe("Keeping the configuration that was loaded before.");
            error.printStackTrace();

            this.startNameTags();
            return false;
        }

        this.startNameTags();

        getLogger().info("Successfully reloaded!");
        return true;
    }

    /**
     * Creates a name tag for every player that is currently online and (re)starts the scheduler.
     */
    private void startNameTags() {
        if (!this.config().nametag().isEnabled()) {
            return;
        }

        final NameTagManager manager = this.nameTagManager();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            manager.createNameTag(player).tick();
        }

        this.nameTagScheduler().start();
    }

    public static DisplayTags get() {
        return Objects.requireNonNull(INSTANCE, "DisplayTags.get() called before onLoad");
    }

    public DisplayTagsConfiguration config() {
        return Objects.requireNonNull(this.config, "DisplayTags configuration could not be read");
    }

    @Override
    public NameTagManager getNameTagManager() {
        return this.nameTagManager();
    }

    private NameTagManager nameTagManager() {
        return Objects.requireNonNull(this.nameTagManager, "DisplayTags has not started up");
    }

    private NameTagScheduler nameTagScheduler() {
        return Objects.requireNonNull(this.nameTagScheduler, "DisplayTags has not started up");
    }
}
