package me.skyyiscool.displaytags;

import me.skyyiscool.displaytags.api.DisplayTagsPlugin;
import me.skyyiscool.displaytags.api.nametag.NameTagManager;
import me.skyyiscool.displaytags.api.nametag.PlayerNameTag;
import me.skyyiscool.displaytags.commands.DisplayTagsCommand;
import me.skyyiscool.displaytags.config.ConfigurationMigrator;
import me.skyyiscool.displaytags.config.DisplayTagsConfiguration;
import me.skyyiscool.displaytags.listener.PlayerListener;
import me.skyyiscool.displaytags.metrics.Metrics;
import me.skyyiscool.displaytags.nametag.NameTagManagerImpl;
import me.skyyiscool.displaytags.nametag.NameTagScheduler;
import me.skyyiscool.displaytags.util.DependencyUtil;
import me.skyyiscool.displaytags.util.MessageUtil;
import me.skyyiscool.displaytags.util.TabUtil;
import me.skyyiscool.displaytags.util.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class DisplayTags extends JavaPlugin implements DisplayTagsPlugin {
    private static final String MODRINTH_PROJECT_ID = "voqEPXf8";

    private static DisplayTags INSTANCE;
    private Metrics metrics;
    private UpdateChecker updateChecker;

    private DisplayTagsConfiguration config;
    private NameTagManager nameTagManager;
    private NameTagScheduler nameTagScheduler;

    @Override
    public void onLoad() {
        INSTANCE = this;

        try {
            // Has to happen before the configuration is read, otherwise Spec rewrites the file
            // with its defaults and a v1 configuration is lost silently.
            ConfigurationMigrator.migrate(this);

            this.config = new DisplayTagsConfiguration(this);
        } catch (Exception error) {
            // A bad value in config.yml must not take the server down with a raw stack trace.
            // The plugin is disabled in onEnable, which Bukkit calls either way.
            this.config = null;
            getLogger().severe("Could not read plugins/DisplayTags/config.yml:");
            getLogger().severe("  " + error.getMessage());
            return;
        }

        this.nameTagManager = new NameTagManagerImpl();
        this.nameTagScheduler = new NameTagScheduler(this);
    }

    @Override
    public void onEnable() {
        if (this.config == null) {
            getLogger().severe("DisplayTags is not starting up because its configuration could not be read.");
            getLogger().severe("Correct the error reported above in plugins/DisplayTags/config.yml, then restart the server.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            // Plugin startup logic
            DependencyUtil.load(this);

            PluginManager pluginManager = getServer().getPluginManager();
            CommandMap commandMap = getServer().getCommandMap();

            // Plugin Integrations
            TabUtil.load(this);

            // Name Tags
            this.nameTagScheduler.start();

            // Register Listeners & Commands
            pluginManager.registerEvents(new PlayerListener(this), this);
            commandMap.register("displaytags", new DisplayTagsCommand(this));

            // Metrics
            this.metrics = new Metrics(this, 29009);

            // Updates
            this.updateChecker = new UpdateChecker(this, MODRINTH_PROJECT_ID);
            this.checkForUpdates(getServer().getConsoleSender());
        } catch (Exception error) {
            getLogger().severe("DisplayTags failed to start up: " + error);
            error.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String version = getPluginMeta().getVersion();
        getLogger().info(String.format("Enabled DisplayTags v%s.", version));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic. Every field here stays null when onEnable (or onLoad) bailed out
        // early, and Bukkit still calls onDisable in that case - so nothing may be dereferenced
        // unguarded.
        if (this.nameTagScheduler != null) this.nameTagScheduler.end();

        // The displays only exist on the clients, so the server forgetting about them is not enough:
        // without an explicit despawn they would linger until the viewer reconnects.
        if (this.nameTagManager != null) this.removeAllNameTags();

        if (this.metrics != null) this.metrics.shutdown();

        getLogger().info("Disabled DisplayTags.");
    }

    /**
     * Removes every name tag that is currently registered, despawning its display for all viewers
     * and handing the vanilla name tags back.
     */
    private void removeAllNameTags() {
        for (PlayerNameTag tag : List.copyOf(this.nameTagManager.getAll())) {
            this.nameTagManager.removeNameTag(tag.getPlayer());
        }
    }

    public void checkForUpdates(CommandSender sender) {
        String current = getPluginMeta().getVersion();

        this.updateChecker.getLatestVersion((latest) -> {
            if (latest == null) return;

            // Comparing the strings for equality would flag every build that is newer than the
            // latest release - a development build of 2.0.0 against a released 1.1.5, for example -
            // as outdated.
            int comparison = UpdateChecker.compare(current, latest);

            if (comparison == 0) {
                MessageUtil.success(sender, "This server is using the latest version of DisplayTags (v" + latest + ").");
                return;
            }

            if (comparison > 0) {
                MessageUtil.success(sender, "This server is running DisplayTags v" + current + ", which is newer than the latest release (v" + latest + ").");
                return;
            }

            String url = "https://modrinth.com/plugin/displaytags/version/" + latest;
            MessageUtil.warning(sender, "This server is running an outdated version of DisplayTags (v" + current + ")");
            MessageUtil.warning(sender, "<u><click:open_url:'" + url + "'><hover:show_text:'<#00BFFF>➡ <reset><u>" + url + "'>Click to download the latest version (v" + latest + ")");
        });
    }

    public boolean reloadPlugin() {
        getLogger().info("Reloading DisplayTags...");

        this.nameTagScheduler.end();

        // Drop the old tags entirely instead of only despawning them. They were built from the
        // previous configuration and the fresh ones below replace them; leaving them registered
        // would keep stale entries around for every player whose tag is not recreated (for
        // instance when name tags end up disabled by the new configuration).
        this.removeAllNameTags();

        try {
            this.config.reload();
        } catch (IllegalArgumentException error) {
            // A rejected value never reaches the live configuration, so the previous one is still
            // intact and the plugin can simply carry on with it.
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
        if (!this.config().nametag().isEnabled()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            this.nameTagManager.createNameTag(player).tick();
        }

        this.nameTagScheduler.start();
    }

    public static DisplayTags get() {
        return INSTANCE;
    }

    public DisplayTagsConfiguration config() {
        return this.config;
    }

    @Override
    public NameTagManager getNameTagManager() {
        return this.nameTagManager;
    }
}
