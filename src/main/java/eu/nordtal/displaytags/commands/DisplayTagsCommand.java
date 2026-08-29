package eu.nordtal.displaytags.commands;

import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.commands.displaytags.ConfigCommand;
import eu.nordtal.displaytags.commands.displaytags.HelpCommand;
import eu.nordtal.displaytags.commands.displaytags.ReloadCommand;
import eu.nordtal.displaytags.commands.framework.CommandGroup;
import eu.nordtal.displaytags.util.MessageUtil;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class DisplayTagsCommand extends CommandGroup {
    public DisplayTagsCommand(DisplayTags plugin) {
        super("displaytags", plugin);
        this.setAliases(List.of("dt"));

        this.addCommand(new ReloadCommand(this));
        this.addCommand(new ConfigCommand(this));
        this.addCommand(new HelpCommand(this));
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String @NotNull [] args) {
        if (args.length == 0) {
            MessageUtil.send(sender, "This server is running <#00BFFF>DisplayTags <gray>v" + this.plugin.getPluginMeta().getVersion() + "<white>!");
            MessageUtil.send(sender, "Run <gray>'/displaytags help' <white>for a full list of commands.");
            return true;
        }

        return super.execute(sender, commandLabel, args);
    }
}
