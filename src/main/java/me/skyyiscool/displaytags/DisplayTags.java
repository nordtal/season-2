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

        // Has to happen before the configuration is read, otherwise Spec rewrites the file
        // with its defaults and a v1 configuration is lost silently.
        ConfigurationMigrator.migrate(this);

        this.config = new DisplayTagsConfiguration(this);
        this.nameTagManager = new NameTagManagerImpl();
        this.nameTagScheduler = new NameTagScheduler(this);
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        DependencyUtil.load(this);

        PluginManager pluginManager = getServer().getPluginManager();
        CommandMap commandMap = getServer().getCommandMap();

        // Configuration
        this.config.load();

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

        String version = getPluginMeta().getVersion();
        getLogger().info(String.format("Enabled DisplayTags v%s.", version));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic

        this.nameTagScheduler.end();
        this.metrics.shutdown();

        getLogger().info("Disabled DisplayTags.");
    }

    public void checkForUpdates(CommandSender sender) {
        String current = getPluginMeta().getVersion();

        this.updateChecker.getLatestVersion((latest) -> {
            if (latest == null) return;

            if (latest.equals(current)) {
                MessageUtil.success(sender, "This server is using the latest version of DisplayTags (v" + latest + ").");
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
        for (PlayerNameTag tag : this.nameTagManager.getAll()) {
            tag.despawnForViewers();
        }

        try {
            this.config.reload();
        } catch (Exception error) {
            getLogger().severe("Failed to reload plugin configuration:" + error.getMessage());
            error.printStackTrace();

            return false;
        }

        if (this.config().nametag().isEnabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.nameTagManager.createNameTag(player).tick();
            }

            this.nameTagScheduler.start();
        }

        getLogger().info("Successfully reloaded!");
        return true;
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
